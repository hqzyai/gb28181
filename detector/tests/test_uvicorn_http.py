"""Real loopback HTTP tests, including the Ultralytics PIL monkeypatch regression."""
import base64
import io
import json
import socket
import threading
import time
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch

import uvicorn
from PIL import Image

from detector_service.app import MAX_BODY_BYTES, Settings, create_app
from test_contract import FakeEngine, TOKEN


class UvicornHttpTests(unittest.TestCase):
    def setUp(self):
        self.open_calls = []
        self.original_open = Image.open

        def forbidden_generic_open(*_args, **_kwargs):
            self.open_calls.append(True)
            raise ModuleNotFoundError("Simulated Ultralytics HEIF fallback; must never run")

        class PatchedEngine(FakeEngine):
            def load(_engine):
                Image.open = forbidden_generic_open

            def close(_engine):
                Image.open = self.original_open

        self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.socket.bind(("127.0.0.1", 0))
        self.socket.listen(16)
        self.base = "http://127.0.0.1:" + str(self.socket.getsockname()[1])
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        config = uvicorn.Config(create_app(Settings(api_token=TOKEN), PatchedEngine),
                                log_level="info", access_log=False, lifespan="on")
        self.server = uvicorn.Server(config)
        self.thread = threading.Thread(target=self.server.run, kwargs={"sockets": [self.socket]}, daemon=True)
        self.thread.start()
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and not self.server.started:
            time.sleep(0.01)
        self.assertTrue(self.server.started, "Uvicorn did not start")
        self.jpeg = io.BytesIO()
        with Image.new("RGB", (320, 180)) as image:
            image.save(self.jpeg, format="JPEG")

    def tearDown(self):
        self.server.should_exit = True
        self.thread.join(timeout=5)
        self.socket.close()
        Image.open = self.original_open
        self.assertFalse(self.thread.is_alive(), "Uvicorn did not stop")

    def request(self, route, body=None, token=True, content_type="application/json"):
        headers = {"Authorization": "Bearer " + TOKEN} if token else {}
        if body is not None:
            headers["Content-Type"] = content_type
        payload = json.dumps(body).encode() if isinstance(body, dict) else body
        try:
            response = self.opener.open(urllib.request.Request(self.base + route, data=payload, headers=headers), timeout=2)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.code, json.loads(response.read())

    def test_invalid_input_is_fast_400_after_ultralytics_generic_pil_patch(self):
        started = time.monotonic()
        status, _ = self.request("/v1/detect", {"imageData": "invalid"}, token=False)
        self.assertEqual(status, 401)
        for content in (b"not-a-jpeg", b"\xff\xd8not-a-valid-jpeg", self.jpeg.getvalue()[:-12]):
            status, body = self.request("/v1/detect", {"imageData": base64.b64encode(content).decode()})
            self.assertEqual(status, 400)
            self.assertEqual(body["error"]["code"], "invalid_image")
        self.assertLess(time.monotonic() - started, 2)
        self.assertEqual(self.open_calls, [])
        self.assertEqual(self.request("/readyz")[0], 200)
        status, body = self.request("/v1/detect", {"imageData": base64.b64encode(self.jpeg.getvalue()).decode(), "frameId": "tcp-valid-after-invalid"})
        self.assertEqual(status, 200)
        self.assertEqual(body["frameId"], "tcp-valid-after-invalid")
        self.assertEqual((body["imageWidth"], body["imageHeight"]), (320, 180))
        self.assertEqual(self.open_calls, [])

    def test_real_body_limit_and_wrong_content_type(self):
        self.assertEqual(self.request("/v1/detect", b"plain", content_type="text/plain")[0], 415)
        status, body = self.request("/v1/detect", b"0" * (MAX_BODY_BYTES + 1))
        self.assertEqual(status, 413)
        self.assertEqual(body["error"]["code"], "payload_too_large")
        self.assertEqual(self.request("/readyz")[0], 200)

    def test_unexpected_decoder_failure_releases_concurrency_guard(self):
        payload = {"imageData": base64.b64encode(self.jpeg.getvalue()).decode()}
        with patch("detector_service.app.JpegImagePlugin.JpegImageFile", side_effect=RuntimeError("decoder internal failure")):
            status, body = self.request("/v1/detect", payload)
            self.assertEqual(status, 503)
            self.assertEqual(body["error"]["code"], "image_decode_failed")
        status, _ = self.request("/v1/detect", payload)
        self.assertEqual(status, 200)  # Never becomes permanently 429 after decode failure.


if __name__ == "__main__":
    unittest.main()
