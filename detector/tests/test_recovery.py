import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor

from fastapi.testclient import TestClient

from detector_service.app import Settings, create_app
from detector_service.engine import EngineUnavailable
from test_contract import BrokenEngine, FakeEngine, HEADERS, MissingEngine, TOKEN, jpeg


class RecoveryTests(unittest.TestCase):
    def test_runtime_oom_exits_after_bounded_delay(self):
        exits = []
        exited = threading.Event()

        def fake_exit(code):
            exits.append(code)
            exited.set()

        settings = Settings(api_token=TOKEN, recovery_delay_seconds=0.03)
        with TestClient(create_app(settings, BrokenEngine, fake_exit)) as client:
            response = client.post("/v1/detect", headers=HEADERS, json={"imageData": jpeg()})
            self.assertEqual(response.status_code, 503)
            self.assertEqual(client.get("/readyz").status_code, 503)
            self.assertTrue(exited.wait(timeout=1))
            self.assertEqual(exits, [70])

    def test_unfinished_timeout_exits_with_71(self):
        finish = threading.Event()
        exits = []
        exited = threading.Event()

        class HungEngine(FakeEngine):
            def detect(self, _image, _confidence):
                finish.wait(timeout=2)
                return []

        def fake_exit(code):
            exits.append(code)
            exited.set()

        settings = Settings(api_token=TOKEN, timeout_seconds=0.02, recovery_delay_seconds=0.03)
        with TestClient(create_app(settings, HungEngine, fake_exit)) as client:
            try:
                response = client.post("/v1/detect", headers=HEADERS, json={"imageData": jpeg()})
                self.assertEqual(response.status_code, 503)
                self.assertEqual(response.json()["error"]["code"], "inference_timeout")
                self.assertTrue(exited.wait(timeout=1))
                self.assertEqual(exits, [71])
            finally:
                finish.set()

    def test_old_timeout_ticket_cannot_kill_new_operation(self):
        first_finish = threading.Event()
        second_finish = threading.Event()
        second_entered = threading.Event()
        exits = []

        class TicketEngine(FakeEngine):
            def __init__(self, settings):
                super().__init__(settings)
                self.calls = 0

            def detect(self, _image, _confidence):
                self.calls += 1
                if self.calls == 1:
                    first_finish.wait(timeout=2)
                else:
                    second_entered.set()
                    second_finish.wait(timeout=2)
                return []

        settings = Settings(api_token=TOKEN, timeout_seconds=0.3, recovery_delay_seconds=0.1)
        with TestClient(create_app(settings, TicketEngine, exits.append)) as client, ThreadPoolExecutor(max_workers=1) as pool:
            first = client.post("/v1/detect", headers=HEADERS, json={"imageData": jpeg()})
            self.assertEqual(first.status_code, 503)
            first_finish.set()
            for _ in range(100):
                if client.get("/readyz").status_code == 200:
                    break
                time.sleep(0.002)
            second = pool.submit(client.post, "/v1/detect", headers=HEADERS, json={"imageData": jpeg()})
            try:
                self.assertTrue(second_entered.wait(timeout=1))
                time.sleep(0.14)  # Old grace expires while the new operation owns the GPU lock.
                self.assertEqual(exits, [])
            finally:
                second_finish.set()
            self.assertEqual(second.result(timeout=1).status_code, 200)

    def test_configuration_error_does_not_restart_loop(self):
        exits = []
        settings = Settings(api_token=TOKEN, recovery_delay_seconds=0.02)
        with TestClient(create_app(settings, MissingEngine, exits.append)) as client:
            self.assertEqual(client.get("/readyz").status_code, 503)
            time.sleep(0.04)
            self.assertEqual(exits, [])

    def test_normal_shutdown_cancels_scheduled_recovery(self):
        exits = []
        settings = Settings(api_token=TOKEN, recovery_delay_seconds=0.1)
        with TestClient(create_app(settings, BrokenEngine, exits.append)) as client:
            self.assertEqual(client.post("/v1/detect", headers=HEADERS, json={"imageData": jpeg()}).status_code, 503)
        time.sleep(0.12)
        self.assertEqual(exits, [])

    def test_restart_warmup_oom_can_retry_without_manual_intervention(self):
        exited = threading.Event()
        exits = []

        class StartupOom(FakeEngine):
            def load(self):
                raise EngineUnavailable("gpu_oom", "GPU 内存不足")

        def fake_exit(code):
            exits.append(code)
            exited.set()

        settings = Settings(api_token=TOKEN, recovery_delay_seconds=0.02)
        with TestClient(create_app(settings, StartupOom, fake_exit)) as client:
            self.assertEqual(client.get("/readyz").status_code, 503)
            self.assertTrue(exited.wait(timeout=1))
            self.assertEqual(exits, [70])


if __name__ == "__main__":
    unittest.main()
