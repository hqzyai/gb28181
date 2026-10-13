import tempfile
import unittest
from contextlib import nullcontext
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from PIL import Image

from detector_service.app import Settings
from detector_service.classes import CLASS_NAMES
from detector_service.engine import EngineUnavailable, UltralyticsEngine


class Tensor:
    def __init__(self, values):
        self.values = values

    def detach(self):
        return self

    def cpu(self):
        return self

    def tolist(self):
        return self.values


class FakeOom(RuntimeError):
    pass


def fake_torch():
    return SimpleNamespace(
        cuda=SimpleNamespace(
            is_available=lambda: True,
            get_device_properties=lambda _device: SimpleNamespace(total_memory=100 * 1024**3),
            set_per_process_memory_fraction=lambda *_args, **_kwargs: None,
            synchronize=lambda _device: None,
            empty_cache=lambda: None,
            OutOfMemoryError=FakeOom,
        ),
        set_num_threads=lambda _count: None,
        inference_mode=nullcontext,
    )


class MappingTests(unittest.TestCase):
    def test_ultralytics_original_coordinates_and_fixed_prediction_head(self):
        calls = []

        class Model:
            names = dict(enumerate(CLASS_NAMES))

            def predict(self, **kwargs):
                calls.append(kwargs)
                image = kwargs["source"]
                return [SimpleNamespace(
                    orig_shape=(image.height, image.width),
                    boxes=SimpleNamespace(
                        xyxy=Tensor([[-5.0, 5.0, image.width + 3.0, image.height + 2.0]]),
                        conf=Tensor([0.9]), cls=Tensor([7.0]),
                    ),
                )]

        with tempfile.TemporaryDirectory() as directory:
            weights = Path(directory) / "best.pt"
            weights.write_bytes(b"test mock checkpoint, no Torch loading")
            settings = Settings(api_token="x" * 32, model_path=str(weights))
            with patch.dict("sys.modules", {"torch": fake_torch(), "ultralytics": SimpleNamespace(YOLO=lambda *_a, **_k: Model())}):
                engine = UltralyticsEngine(settings)
                engine.load()
                with Image.new("RGB", (2560, 1440)) as image:
                    boxes = engine.detect(image, 0.3)
        self.assertEqual(len(calls), 2)  # real load path includes warmup
        self.assertFalse(calls[1]["nms"])
        self.assertFalse(calls[1]["rect"])
        self.assertEqual(calls[1]["device"], 0)
        self.assertEqual(boxes[0]["x1"], 0)
        self.assertEqual(boxes[0]["x2"], 2560)
        self.assertEqual(boxes[0]["y2"], 1440)
        self.assertEqual(boxes[0]["classCode"], "no_helmet")
        self.assertEqual(boxes[0]["riskType"], "未戴安全帽")
        self.assertEqual(len(engine.sha256), 64)

    def test_coco_class_names_rejected_before_warmup(self):
        class CocoModel:
            names = {0: "person", 1: "bicycle"}

        with tempfile.TemporaryDirectory() as directory:
            weights = Path(directory) / "best.pt"
            weights.write_bytes(b"COCO mock")
            with patch.dict("sys.modules", {"torch": fake_torch(), "ultralytics": SimpleNamespace(YOLO=lambda *_a, **_k: CocoModel())}):
                engine = UltralyticsEngine(Settings(api_token="x" * 32, model_path=str(weights)))
                with self.assertRaises(EngineUnavailable) as error:
                    engine.load()
        self.assertEqual(error.exception.code, "class_mismatch")

    def test_missing_model_does_not_import_or_download_torch(self):
        engine = UltralyticsEngine(Settings(api_token="x" * 32, model_path="/nonexistent/ppe-best.pt"))
        with self.assertRaises(EngineUnavailable) as error:
            engine.load()
        self.assertEqual(error.exception.code, "model_missing")


if __name__ == "__main__":
    unittest.main()
