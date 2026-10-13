from __future__ import annotations

import hashlib
import math
from pathlib import Path

from PIL import Image

from .classes import CLASS_NAMES, class_metadata


class EngineUnavailable(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


class UltralyticsEngine:
    """CUDA-only, single-image inference; imports Torch only when weights load."""

    def __init__(self, settings):
        self.settings = settings
        self.model = None
        self.sha256 = ""
        self.version = settings.model_version

    def load(self) -> None:
        path = Path(self.settings.model_path)
        if not path.is_file() or path.suffix != ".pt":
            raise EngineUnavailable("model_missing", "PPE 模型权重未就绪")
        digest = hashlib.sha256()
        with path.open("rb") as weights:
            for chunk in iter(lambda: weights.read(1024 * 1024), b""):
                digest.update(chunk)
        self.sha256 = digest.hexdigest()
        expected = self.settings.expected_sha256
        if expected and self.sha256 != expected:
            raise EngineUnavailable("model_hash_mismatch", "模型权重校验失败")
        self.version = self.settings.model_version or f"{self.settings.model_name}-{self.sha256[:12]}"

        import torch
        from ultralytics import YOLO

        if not torch.cuda.is_available():
            raise EngineUnavailable("cuda_unavailable", "CUDA 设备不可用")
        torch.set_num_threads(self.settings.cpu_threads)
        total_memory = torch.cuda.get_device_properties(0).total_memory
        memory_fraction = min(1.0, self.settings.gpu_memory_mib * 1024 * 1024 / total_memory)
        torch.cuda.set_per_process_memory_fraction(memory_fraction, device=0)
        self.model = YOLO(str(path), task="detect")
        names = self.model.names
        ordered_names = tuple(names[index] for index in range(len(names)))
        if ordered_names != CLASS_NAMES:
            self.model = None
            raise EngineUnavailable("class_mismatch", "权重类别与 Construction-PPE 不匹配")
        with Image.new("RGB", (self.settings.image_size, self.settings.image_size)) as warmup:
            self.detect(warmup, self.settings.default_confidence)
        torch.cuda.synchronize(0)

    def detect(self, image: Image.Image, confidence: float) -> list[dict]:
        import torch

        if self.model is None:
            raise EngineUnavailable("model_not_ready", "模型尚未加载")
        try:
            with torch.inference_mode():
                results = self.model.predict(
                    source=image, device=0, imgsz=self.settings.image_size,
                    conf=confidence, iou=0.7, max_det=self.settings.max_detections,
                    rect=False, nms=False, verbose=False, save=False, stream=False,
                )
                result = results[0]
                if tuple(result.orig_shape) != (image.height, image.width):
                    raise EngineUnavailable("invalid_model_output", "模型输出尺寸不匹配")
                boxes = result.boxes
                if boxes is None:
                    raise EngineUnavailable("invalid_model_output", "模型未返回检测结果")
                # Ultralytics xyxy already maps the letterboxed tensor to original pixels.
                coordinates = boxes.xyxy.detach().cpu().tolist()
                confidences = boxes.conf.detach().cpu().tolist()
                classes = boxes.cls.detach().cpu().tolist()
                if not (len(coordinates) == len(confidences) == len(classes)):
                    raise EngineUnavailable("invalid_model_output", "模型输出不完整")
                output = []
                for xyxy, score, raw_class in zip(coordinates, confidences, classes):
                    if not all(math.isfinite(float(value)) for value in [*xyxy, score, raw_class]):
                        raise EngineUnavailable("invalid_model_output", "模型输出非有限数值")
                    class_id = int(raw_class)
                    if class_id != raw_class or not 0 <= class_id < len(CLASS_NAMES) or not 0 <= score <= 1:
                        raise EngineUnavailable("invalid_model_output", "模型类别或置信度无效")
                    x1, y1, x2, y2 = xyxy
                    x1, x2 = max(0.0, min(image.width, x1)), max(0.0, min(image.width, x2))
                    y1, y2 = max(0.0, min(image.height, y1)), max(0.0, min(image.height, y2))
                    if x2 <= x1 or y2 <= y1:
                        raise EngineUnavailable("invalid_model_output", "模型检测框无效")
                    output.append({
                        **class_metadata(class_id), "confidence": float(score),
                        "x1": round(x1, 3), "y1": round(y1, 3),
                        "x2": round(x2, 3), "y2": round(y2, 3),
                    })
                return output
        except torch.cuda.OutOfMemoryError as error:
            torch.cuda.empty_cache()
            raise EngineUnavailable("gpu_oom", "GPU 内存不足，检测服务需恢复后重试") from error

    def close(self) -> None:
        self.model = None
