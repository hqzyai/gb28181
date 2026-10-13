from __future__ import annotations

import asyncio
import base64
import binascii
import hmac
import io
import logging
import math
import os
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from PIL import Image, ImageOps, JpegImagePlugin, UnidentifiedImageError
from pydantic import BaseModel, ConfigDict, Field, field_validator

from .classes import all_classes
from .engine import EngineUnavailable, UltralyticsEngine
from .thresholds import load_thresholds

LOGGER = logging.getLogger("uvicorn.error.ppe_detector")
MAX_IMAGE_BYTES = 3 * 1024 * 1024
MAX_BODY_BYTES = 4 * 1024 * 1024 + 16 * 1024


@dataclass(frozen=True)
class Settings:
    api_token: str
    model_path: str = "/models/best.pt"
    thresholds_path: str = ""
    model_name: str = "construction-ppe-yolo26s"
    model_version: str = ""
    expected_sha256: str = ""
    image_size: int = 640
    default_confidence: float = 0.25
    timeout_seconds: float = 15.0
    recovery_delay_seconds: float = 30.0
    max_dimension: int = 4096
    max_pixels: int = 8_388_608
    max_detections: int = 300
    gpu_memory_mib: int = 2048
    cpu_threads: int = 2

    @classmethod
    def from_environment(cls):
        token = os.environ.get("DETECTOR_API_TOKEN", "")
        token_file = os.environ.get("DETECTOR_API_TOKEN_FILE", "")
        if token_file:
            token = Path(token_file).read_text(encoding="utf-8").strip()
        if len(token) < 32:
            raise ValueError("DETECTOR_API_TOKEN 必须为至少 32 个字符的随机凭据")
        settings = cls(
            api_token=token,
            model_path=os.environ.get("DETECTOR_MODEL_PATH", "/models/best.pt"),
            thresholds_path=os.environ.get("DETECTOR_THRESHOLDS_PATH", ""),
            model_name=os.environ.get("DETECTOR_MODEL_NAME", "construction-ppe-yolo26s"),
            model_version=os.environ.get("DETECTOR_MODEL_VERSION", ""),
            expected_sha256=os.environ.get("DETECTOR_MODEL_SHA256", "").lower(),
            image_size=int(os.environ.get("DETECTOR_IMAGE_SIZE", "640")),
            default_confidence=float(os.environ.get("DETECTOR_CONFIDENCE", "0.25")),
            timeout_seconds=float(os.environ.get("DETECTOR_TIMEOUT_SECONDS", "15")),
            recovery_delay_seconds=float(os.environ.get("DETECTOR_RECOVERY_DELAY_SECONDS", "30")),
            gpu_memory_mib=int(os.environ.get("DETECTOR_GPU_MEMORY_MIB", "2048")),
        )
        if not 320 <= settings.image_size <= 1280 or settings.image_size % 32:
            raise ValueError("DETECTOR_IMAGE_SIZE 必须是 320..1280 范围内的 32 倍数")
        if not math.isfinite(settings.default_confidence) or not 0.01 <= settings.default_confidence <= 0.99:
            raise ValueError("DETECTOR_CONFIDENCE 必须在 0.01..0.99 范围内")
        if not math.isfinite(settings.timeout_seconds) or not 0.1 <= settings.timeout_seconds <= 120:
            raise ValueError("DETECTOR_TIMEOUT_SECONDS 必须在 0.1..120 范围内")
        if not math.isfinite(settings.recovery_delay_seconds) or not 5 <= settings.recovery_delay_seconds <= 300:
            raise ValueError("DETECTOR_RECOVERY_DELAY_SECONDS 必须在 5..300 范围内")
        if not 512 <= settings.gpu_memory_mib <= 8192:
            raise ValueError("DETECTOR_GPU_MEMORY_MIB 必须在 512..8192 范围内")
        if settings.expected_sha256 and (
            len(settings.expected_sha256) != 64 or any(c not in "0123456789abcdef" for c in settings.expected_sha256)
        ):
            raise ValueError("DETECTOR_MODEL_SHA256 无效")
        return settings


class DetectRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    imageData: str = Field(min_length=1, max_length=4 * 1024 * 1024 + 32)
    frameId: str | None = Field(default=None, min_length=1, max_length=128)
    capturedAt: datetime | None = None
    confidence: float | None = Field(default=None, ge=0.01, le=0.99)

    @field_validator("confidence", mode="before")
    @classmethod
    def finite_confidence(cls, value):
        if value is not None and (isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value)):
            raise ValueError("confidence 必须为有限数值")
        return value

    @field_validator("capturedAt")
    @classmethod
    def aware_capture_time(cls, value):
        if value is not None and value.tzinfo is None:
            raise ValueError("capturedAt 必须包含时区")
        return value


def error_response(status: int, code: str, message: str, headers: dict | None = None):
    return JSONResponse(status_code=status, content={"error": {"code": code, "message": message}}, headers=headers)


class GuardMiddleware:
    """Authenticate before reading image bytes; cap both declared and streamed bodies."""

    def __init__(self, app, token: str):
        self.app = app
        self.expected = f"Bearer {token}".encode("utf-8")

    async def reject_oversized(self, scope, receive, send):
        # A bounded discard lets a genuine ~4 MiB sender read HTTP 413 instead
        # of BrokenPipe. Never buffer, decode, or drain an arbitrary large body.
        discarded = 0
        try:
            async with asyncio.timeout(2):
                while discarded <= MAX_BODY_BYTES + 64 * 1024:
                    message = await receive()
                    if message["type"] == "http.disconnect":
                        return
                    discarded += len(message.get("body", b""))
                    if not message.get("more_body", False):
                        break
        except TimeoutError:
            pass
        await error_response(413, "payload_too_large", "图像请求超过大小限制", {"Connection": "close"})(scope, receive, send)

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["path"] != "/v1/detect":
            await self.app(scope, receive, send)
            return
        headers = dict(scope["headers"])
        authorization = headers.get(b"authorization", b"")
        if not hmac.compare_digest(authorization, self.expected):
            await error_response(401, "unauthorized", "检测接口鉴权失败", {"WWW-Authenticate": "Bearer"})(scope, receive, send)
            return
        if headers.get(b"content-type", b"").split(b";", 1)[0].strip().lower() != b"application/json":
            await error_response(415, "content_type", "请使用 application/json")(scope, receive, send)
            return
        length = headers.get(b"content-length")
        if length:
            try:
                size = int(length)
                if size < 0:
                    raise ValueError
            except ValueError:
                await error_response(400, "invalid_length", "请求长度无效")(scope, receive, send)
                return
            if size > MAX_BODY_BYTES:
                await self.reject_oversized(scope, receive, send)
                return
        chunks = []
        total = 0
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body", b"")
            total += len(chunk)
            if total > MAX_BODY_BYTES:
                await error_response(413, "payload_too_large", "图像请求超过大小限制")(scope, receive, send)
                return
            chunks.append(chunk)
            if not message.get("more_body", False):
                break
        body = b"".join(chunks)
        supplied = False

        async def replay():
            nonlocal supplied
            if not supplied:
                supplied = True
                return {"type": "http.request", "body": body, "more_body": False}
            return await receive()

        await self.app(scope, replay, send)


def decode_image(encoded: str, settings: Settings) -> Image.Image:
    if encoded.startswith("data:"):
        prefix, separator, encoded = encoded.partition(",")
        if not separator or prefix.lower() != "data:image/jpeg;base64":
            raise ValueError("只接受 base64 JPEG 图像")
    if len(encoded) > 4 * ((MAX_IMAGE_BYTES + 2) // 3):
        raise OverflowError("JPEG 图像超过 3 MiB 限制")
    try:
        data = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError) as error:
        raise ValueError("图像 base64 编码无效") from error
    if len(data) > MAX_IMAGE_BYTES:
        raise OverflowError("JPEG 图像超过 3 MiB 限制")
    if not data.startswith(b"\xff\xd8"):
        raise ValueError("只接受 JPEG 图像")
    try:
        # Ultralytics patches generic Image.open to auto-install HEIF support on
        # unidentified input. Use the JPEG decoder directly: no plugin fallback,
        # package installation, or network access during input validation.
        with JpegImagePlugin.JpegImageFile(io.BytesIO(data)) as source:
            if source.format != "JPEG":
                raise ValueError("只接受 JPEG 图像")
            width, height = source.size
            if width < 32 or height < 32:
                raise ValueError("图像宽高至少为 32 像素")
            if width > settings.max_dimension or height > settings.max_dimension or width * height > settings.max_pixels:
                raise OverflowError("图像像素尺寸超过限制")
            source.load()
            # Preserve the presented camera frame orientation and report those dimensions.
            return ImageOps.exif_transpose(source).convert("RGB")
    except (UnidentifiedImageError, OSError, SyntaxError, Image.DecompressionBombError) as error:
        raise ValueError("JPEG 图像无法完整解码") from error


def create_app(settings: Settings | None = None, engine_factory: Callable | None = None,
               process_exit: Callable = os._exit) -> FastAPI:
    settings = settings or Settings.from_environment()
    engine = (engine_factory or UltralyticsEngine)(settings)
    guard = threading.Lock()
    executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="ppe-inference")
    state = {"ready": False, "fault": "model_not_ready", "timeoutWork": None,
             "sequence": 0, "activeTicket": None, "recoveryTicket": None}
    lifecycle_guard = threading.Lock()
    shutdown_event = threading.Event()
    thresholds = None

    def schedule_recovery(ticket: int, exit_code: int, reason: str):
        def watchdog():
            if shutdown_event.wait(settings.recovery_delay_seconds):
                return
            # Check and exit while holding the lifecycle lock. A completed/replaced
            # operation cannot be mistaken for the timeout that owns this ticket.
            with lifecycle_guard:
                if shutdown_event.is_set():
                    return
                if exit_code == 71:
                    failed = state["activeTicket"] == ticket and guard.locked()
                else:
                    failed = state["recoveryTicket"] == ticket and not state["ready"]
                if not failed:
                    return
                LOGGER.error("PPE process recovery reason=%s exit_code=%s", reason, exit_code)
                process_exit(exit_code)

        LOGGER.warning("PPE recovery scheduled reason=%s delay_seconds=%s", reason, settings.recovery_delay_seconds)
        threading.Thread(target=watchdog, name="ppe-recovery-watchdog", daemon=True).start()

    @asynccontextmanager
    async def lifespan(_app):
        nonlocal thresholds
        try:
            await asyncio.get_running_loop().run_in_executor(executor, engine.load)
            thresholds = load_thresholds(settings.thresholds_path, engine.sha256)
            state.update(ready=True, fault="")
            LOGGER.info("PPE model warmup succeeded version=%s sha256=%s", engine.version, engine.sha256)
        except EngineUnavailable as error:
            state.update(ready=False, fault=error.code)
            LOGGER.error("PPE model startup unavailable code=%s", error.code)
            # A transient GPU OOM during restart should recover when GPU pressure
            # clears; bad weights/threshold/configuration never cause restart loops.
            if error.code == "gpu_oom":
                state["recoveryTicket"] = 0
                schedule_recovery(0, 70, error.code)
        except Exception:
            state.update(ready=False, fault="startup_failed")
            LOGGER.exception("PPE model startup failed")
        yield
        shutdown_event.set()
        # A timed-out CUDA operation cannot be cancelled safely. Keep the worker alive
        # and its lock held until it finishes; do not admit a second GPU operation.
        executor.shutdown(wait=True, cancel_futures=True)
        engine.close()

    app = FastAPI(title="Construction-PPE internal detector", version="1.0.0", lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)
    app.add_middleware(GuardMiddleware, token=settings.api_token)

    @app.exception_handler(RequestValidationError)
    async def validation_error(_request: Request, _error: RequestValidationError):
        # Validation errors must not echo base64 images or authorization headers.
        return error_response(400, "invalid_request", "请求字段、图像或置信度无效")

    @app.get("/healthz")
    async def health():
        return {"status": "alive"}

    @app.get("/readyz")
    async def ready():
        if not state["ready"]:
            return error_response(503, state["fault"], "PPE 模型尚未就绪，请检查服务日志")
        timed_out = state["timeoutWork"]
        if timed_out is not None and not timed_out.done():
            return error_response(503, "inference_timeout", "超时推理仍在执行，检测服务暂不可用")
        return {"status": "ready", "engine": "yolo", "modelVersion": engine.version, "modelSha256": engine.sha256}

    @app.get("/v1/model")
    async def model():
        public_classes = thresholds.public_classes() if thresholds else [
            {**entry, "confidenceThreshold": settings.default_confidence,
             "calibrationState": "baseline_uncalibrated", "thresholdSource": "request-or-default"}
            for entry in all_classes()
        ]
        return {"modelName": settings.model_name, "modelVersion": engine.version, "sha256": engine.sha256,
                "ready": state["ready"] and (state["timeoutWork"] is None or state["timeoutWork"].done()), "classes": public_classes,
                "thresholdsSha256": thresholds.sha256 if thresholds else None,
                "capabilities": {"noVestDetection": False, "requiresHumanReview": True}}

    @app.post("/v1/detect")
    async def detect(payload: DetectRequest):
        if not state["ready"]:
            return error_response(503, state["fault"], "PPE 模型尚未就绪")
        if not guard.acquire(blocking=False):
            return error_response(429, "busy", "检测任务繁忙，请稍后重试", {"Retry-After": "1"})
        try:
            image = decode_image(payload.imageData, settings)
        except OverflowError as error:
            guard.release()
            return error_response(413, "image_too_large", str(error))
        except ValueError as error:
            guard.release()
            return error_response(400, "invalid_image", str(error))
        except Exception:
            guard.release()
            LOGGER.exception("JPEG decoder failed")
            return error_response(503, "image_decode_failed", "图像解码服务异常，本帧未完成识别")
        frame_id = payload.frameId or str(uuid.uuid4())
        captured_at = payload.capturedAt or datetime.now(timezone.utc)
        confidence = payload.confidence if payload.confidence is not None else settings.default_confidence
        candidate_confidence = thresholds.candidate_confidence(confidence) if thresholds else confidence
        width, height = image.size
        started_at = time.monotonic()
        with lifecycle_guard:
            state["sequence"] += 1
            ticket = state["sequence"]
            state["activeTicket"] = ticket

        def run():
            try:
                boxes = engine.detect(image, candidate_confidence)
                return thresholds.filter_boxes(boxes, confidence) if thresholds else boxes
            except EngineUnavailable as error:
                state.update(ready=False, fault=error.code)
                if error.code == "gpu_oom":
                    with lifecycle_guard:
                        state["recoveryTicket"] = ticket
                    schedule_recovery(ticket, 70, error.code)
                raise
            except Exception:
                state.update(ready=False, fault="inference_failed")
                with lifecycle_guard:
                    state["recoveryTicket"] = ticket
                schedule_recovery(ticket, 70, "inference_failed")
                raise
            finally:
                image.close()
                with lifecycle_guard:
                    if state["activeTicket"] == ticket:
                        state["activeTicket"] = None
                guard.release()

        work = asyncio.get_running_loop().run_in_executor(executor, run)
        try:
            boxes = await asyncio.wait_for(asyncio.shield(work), timeout=settings.timeout_seconds)
        except asyncio.TimeoutError:
            # Consume an eventual exception after a timed-out/disconnected caller.
            state["timeoutWork"] = work
            schedule_recovery(ticket, 71, "inference_timeout")
            work.add_done_callback(lambda done: done.exception() if not done.cancelled() else None)
            LOGGER.warning("PPE inference timeout frame=%s", frame_id)
            return error_response(503, "inference_timeout", "检测推理超时，请稍后重试")
        except asyncio.CancelledError:
            work.add_done_callback(lambda done: done.exception() if not done.cancelled() else None)
            raise
        except EngineUnavailable as error:
            state.update(ready=False, fault=error.code)
            LOGGER.error("PPE inference unavailable code=%s frame=%s", error.code, frame_id)
            return error_response(503, error.code, str(error))
        except Exception:
            state.update(ready=False, fault="inference_failed")
            LOGGER.exception("PPE inference failed frame=%s", frame_id)
            return error_response(503, "inference_failed", "检测推理失败，请检查服务日志")
        return {"frameId": frame_id, "capturedAt": captured_at.isoformat(),
                "detectedAt": datetime.now(timezone.utc).isoformat(), "imageWidth": width, "imageHeight": height,
                "engine": "yolo", "modelVersion": engine.version, "modelSha256": engine.sha256,
                "thresholdsSha256": thresholds.sha256 if thresholds else None,
                "inferenceMs": round((time.monotonic() - started_at) * 1000, 3), "boxes": boxes}

    return app
