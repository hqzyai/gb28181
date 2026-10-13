import base64
import io
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace

from fastapi.testclient import TestClient
from PIL import Image

from detector_service.app import MAX_BODY_BYTES, MAX_IMAGE_BYTES, Settings, create_app
from detector_service.classes import class_metadata
from detector_service.engine import EngineUnavailable


TOKEN = "test-token-for-contract-only-" + "x" * 32
HEADERS = {"Authorization": f"Bearer {TOKEN}"}


def jpeg(width=320, height=180, format="JPEG"):
    with Image.new("RGB", (width, height), "#555555") as image:
        data = io.BytesIO()
        image.save(data, format=format)
    return base64.b64encode(data.getvalue()).decode("ascii")


class FakeEngine:
    def __init__(self, _settings):
        self.sha256 = "a" * 64
        self.version = "ppe-test-version"
        self.closed = False
        self.last_confidence = None

    def load(self):
        pass

    def detect(self, image, confidence):
        self.last_confidence = confidence
        return [{**class_metadata(7), "confidence": 0.91, "x1": 20.0, "y1": 30.0, "x2": 110.0, "y2": 130.0},
                {**class_metadata(0), "confidence": 0.85, "x1": 200.0, "y1": 20.0, "x2": 220.0, "y2": 60.0}]

    def close(self):
        self.closed = True


class MissingEngine(FakeEngine):
    def load(self):
        raise EngineUnavailable("model_missing", "PPE 模型权重未就绪")


class BrokenEngine(FakeEngine):
    def detect(self, _image, _confidence):
        raise EngineUnavailable("gpu_oom", "GPU 内存不足")


class ContractTests(unittest.TestCase):
    def setUp(self):
        self.settings = Settings(api_token=TOKEN)
        self.context = TestClient(create_app(self.settings, FakeEngine))
        self.client = self.context.__enter__()

    def tearDown(self):
        self.context.__exit__(None, None, None)

    def test_image_coordinates_classification_and_frame_identity(self):
        response = self.client.post("/v1/detect", headers=HEADERS,
                                    json={"imageData": jpeg(), "frameId": "camera-1-0009",
                                          "capturedAt": "2026-10-12T14:00:00+08:00", "confidence": 0.3})
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual((body["imageWidth"], body["imageHeight"]), (320, 180))
        self.assertEqual(body["frameId"], "camera-1-0009")
        self.assertEqual(body["capturedAt"], "2026-10-12T14:00:00+08:00")
        self.assertEqual(body["engine"], "yolo")
        self.assertEqual(body["modelVersion"], "ppe-test-version")
        self.assertEqual(body["boxes"][0]["classCode"], "no_helmet")
        self.assertEqual(body["boxes"][0]["riskType"], "未戴安全帽")
        self.assertEqual(body["boxes"][0]["x2"], 110.0)
        self.assertEqual(body["boxes"][1]["kind"], "object")
        self.assertIsNone(body["boxes"][1]["riskType"])
        self.assertGreaterEqual(body["inferenceMs"], 0)

    def test_jpeg_data_uri(self):
        response = self.client.post("/v1/detect", headers=HEADERS,
                                    json={"imageData": "data:image/jpeg;base64," + jpeg()})
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json()["frameId"])

    def test_authentication_precedes_body_parsing(self):
        response = self.client.post("/v1/detect", content=b"not-json", headers={"Content-Type": "application/json"})
        self.assertEqual(response.status_code, 401)
        self.assertNotIn(TOKEN, response.text)
        self.assertNotIn("not-json", response.text)

    def test_health_and_public_model_do_not_disclose_secrets(self):
        self.assertEqual(self.client.get("/healthz").status_code, 200)
        self.assertEqual(self.client.get("/readyz").status_code, 200)
        response = self.client.get("/v1/model")
        body = response.json()
        self.assertEqual(len(body["classes"]), 11)
        self.assertFalse(body["capabilities"]["noVestDetection"])
        self.assertEqual(body["classes"][5]["kind"], "unknown")
        self.assertIsNone(body["classes"][5]["riskType"])
        self.assertNotIn(TOKEN, response.text)
        self.assertNotIn("no_vest", response.text)
        self.assertNotIn("/models", response.text)

    def test_invalid_images_fail_instead_of_empty_detections(self):
        for image in ["%%%", "aGVsbG8=", base64.b64encode(b"\xff\xd8not-jpeg").decode(), jpeg(format="PNG"), jpeg()[:-12], "data:image/png;base64," + jpeg()]:
            with self.subTest(image=image[:24]):
                response = self.client.post("/v1/detect", headers=HEADERS, json={"imageData": image})
                self.assertEqual(response.status_code, 400)
                self.assertIn("error", response.json())
                self.assertNotIn("boxes", response.json())

    def test_invalid_confidences_are_rejected_without_echoing_image(self):
        encoded = jpeg()
        for confidence in [-0.1, 1.01, "0.3", True]:
            with self.subTest(confidence=confidence):
                response = self.client.post("/v1/detect", headers=HEADERS,
                                            json={"imageData": encoded, "confidence": confidence})
                self.assertEqual(response.status_code, 400)
                self.assertNotIn(encoded, response.text)
        response = self.client.post("/v1/detect", headers={**HEADERS, "Content-Type": "application/json"},
                                    content='{"imageData":"' + encoded + '","confidence":NaN}')
        self.assertEqual(response.status_code, 400)

    def test_unknown_fields_and_naive_timestamp_rejected(self):
        for fields in [{"sourceUrl": "http://example.test"}, {"capturedAt": "2026-10-12T14:00:00"}]:
            with self.subTest(fields=fields):
                response = self.client.post("/v1/detect", headers=HEADERS, json={"imageData": jpeg(), **fields})
                self.assertEqual(response.status_code, 400)

    def test_oversize_decoded_image_is_413(self):
        encoded = base64.b64encode(b"x" * (MAX_IMAGE_BYTES + 1)).decode()
        response = self.client.post("/v1/detect", headers=HEADERS, json={"imageData": encoded})
        self.assertEqual(response.status_code, 413)
        response = self.client.post("/v1/detect", headers=HEADERS, json={"imageData": jpeg(4097, 32)})
        self.assertEqual(response.status_code, 413)

    def test_body_limit_and_content_type(self):
        response = self.client.post("/v1/detect", headers={**HEADERS, "Content-Type": "application/json"},
                                    content=b"x" * (MAX_BODY_BYTES + 1))
        self.assertEqual(response.status_code, 413)
        self.assertEqual(self.client.post("/v1/detect", headers=HEADERS, content=b"x").status_code, 415)

    def test_missing_weights_keeps_liveness_but_not_readiness(self):
        with TestClient(create_app(self.settings, MissingEngine)) as client:
            self.assertEqual(client.get("/healthz").status_code, 200)
            self.assertEqual(client.get("/readyz").status_code, 503)
            response = client.post("/v1/detect", headers=HEADERS, json={"imageData": jpeg()})
            self.assertEqual(response.status_code, 503)
            self.assertEqual(response.json()["error"]["code"], "model_missing")

    def test_gpu_fault_is_not_reported_as_normal_empty_result(self):
        with TestClient(create_app(self.settings, BrokenEngine)) as client:
            response = client.post("/v1/detect", headers=HEADERS, json={"imageData": jpeg()})
            self.assertEqual(response.status_code, 503)
            self.assertEqual(response.json()["error"]["code"], "gpu_oom")
            self.assertEqual(client.get("/readyz").status_code, 503)

    def test_empty_scene_is_a_valid_success(self):
        class EmptyEngine(FakeEngine):
            def detect(self, _image, _confidence):
                return []
        with TestClient(create_app(self.settings, EmptyEngine)) as client:
            response = client.post("/v1/detect", headers=HEADERS, json={"imageData": jpeg()})
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["boxes"], [])

    def test_busy_returns_429_and_timeout_does_not_release_running_gpu(self):
        entered = threading.Event()
        finish = threading.Event()

        class SlowEngine(FakeEngine):
            def detect(self, _image, _confidence):
                entered.set()
                finish.wait(timeout=2)
                return []

        settings = replace(self.settings, timeout_seconds=0.05)
        with TestClient(create_app(settings, SlowEngine)) as client, ThreadPoolExecutor(max_workers=1) as pool:
            first = pool.submit(client.post, "/v1/detect", headers=HEADERS, json={"imageData": jpeg()})
            self.assertTrue(entered.wait(timeout=1))
            response = client.post("/v1/detect", headers=HEADERS, json={"imageData": jpeg()})
            self.assertEqual(response.status_code, 429)
            response = first.result(timeout=1)
            self.assertEqual(response.status_code, 503)
            self.assertEqual(response.json()["error"]["code"], "inference_timeout")
            self.assertEqual(client.get("/readyz").status_code, 503)
            self.assertFalse(client.get("/v1/model").json()["ready"])
            response = client.post("/v1/detect", headers=HEADERS, json={"imageData": jpeg()})
            self.assertEqual(response.status_code, 429)
            finish.set()
            for _ in range(30):
                response = client.post("/v1/detect", headers=HEADERS, json={"imageData": jpeg()})
                if response.status_code == 200:
                    break
                time.sleep(0.01)
            self.assertEqual(response.status_code, 200)
            self.assertEqual(client.get("/readyz").status_code, 200)
            self.assertTrue(client.get("/v1/model").json()["ready"])


if __name__ == "__main__":
    unittest.main()
