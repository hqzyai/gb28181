import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

from fastapi.testclient import TestClient

from detector_service.app import Settings, create_app
from detector_service.classes import CLASS_NAMES, class_metadata
from detector_service.thresholds import load_thresholds
from detector_service.engine import EngineUnavailable
from test_contract import FakeEngine, HEADERS, TOKEN, jpeg


def threshold_document():
    return {
        "schema_version": 1, "source_split": "val", "mode": "val-f1",
        "model_sha256": "a" * 64, "dataset_manifest_sha256": "b" * 64,
        "classes": [{"class_id": index, "class_name": name,
                     "conf_threshold": 0.25 if index == 0 else 0.8,
                     "calibration_state": "val_f1_calibrated",
                     "production_alarm_accepted": False}
                    for index, name in enumerate(CLASS_NAMES)],
    }


class ThresholdTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "thresholds.json"

    def tearDown(self):
        self.directory.cleanup()

    def save(self, document):
        self.path.write_text(json.dumps(document), encoding="utf-8")

    def test_absent_optional_thresholds_preserves_contract(self):
        self.assertIsNone(load_thresholds("", "a" * 64))

    def test_missing_configured_threshold_file_fails_readiness(self):
        settings = Settings(api_token=TOKEN, thresholds_path=str(self.path))
        with TestClient(create_app(settings, FakeEngine)) as client:
            self.assertEqual(client.get("/healthz").status_code, 200)
            response = client.get("/readyz")
            self.assertEqual(response.status_code, 503)
            self.assertEqual(response.json()["error"]["code"], "thresholds_missing")

    def test_model_hash_and_unknown_class_rejected(self):
        for mutation, error_code in [("hash", "thresholds_hash_mismatch"), ("class", "thresholds_class_mismatch")]:
            document = threshold_document()
            if mutation == "hash":
                document["model_sha256"] = "c" * 64
            else:
                document["classes"][7]["class_name"] = "no_vest"
            self.save(document)
            with self.assertRaises(EngineUnavailable) as error:
                load_thresholds(str(self.path), "a" * 64)
            self.assertEqual(error.exception.code, error_code)
            with TestClient(create_app(Settings(api_token=TOKEN, thresholds_path=str(self.path)), FakeEngine)) as client:
                self.assertEqual(client.get("/readyz").status_code, 503)

    def test_invalid_thresholds_duplicates_and_test_split_rejected(self):
        for bad in [float("nan"), float("inf"), True, "0.2", 0.0, 1.0]:
            document = threshold_document()
            document["classes"][0]["conf_threshold"] = bad
            self.save(document)
            with self.assertRaises(EngineUnavailable) as error:
                load_thresholds(str(self.path), "a" * 64)
            self.assertEqual(error.exception.code, "thresholds_invalid")
        document = threshold_document()
        document["classes"][10] = document["classes"][0]
        self.save(document)
        with self.assertRaises(EngineUnavailable) as error:
            load_thresholds(str(self.path), "a" * 64)
        self.assertEqual(error.exception.code, "thresholds_class_mismatch")
        document = threshold_document()
        document["source_split"] = "test"
        self.save(document)
        with self.assertRaises(EngineUnavailable) as error:
            load_thresholds(str(self.path), "a" * 64)
        self.assertEqual(error.exception.code, "thresholds_invalid")

    def test_candidate_generation_and_frozen_per_class_filter(self):
        recorded = []

        class ThresholdEngine(FakeEngine):
            def detect(self, _image, confidence):
                recorded.append(confidence)
                return [{**class_metadata(index), "confidence": score,
                         "x1": 20, "y1": 30, "x2": 90, "y2": 120}
                        for index, score in [(0, 0.3), (0, 0.2499999), (7, 0.7999999), (7, 0.8)]]

        self.save(threshold_document())
        settings = Settings(api_token=TOKEN, thresholds_path=str(self.path))
        with TestClient(create_app(settings, ThresholdEngine)) as client:
            response = client.post("/v1/detect", headers=HEADERS, json={"imageData": jpeg(), "confidence": 0.01})
            self.assertEqual(response.status_code, 200)
            self.assertEqual(recorded[-1], 0.01)
            self.assertEqual([box["confidence"] for box in response.json()["boxes"]], [0.3, 0.8])
            metadata = client.get("/v1/model").json()
            self.assertEqual(metadata["classes"][7]["confidenceThreshold"], 0.8)
            self.assertEqual(metadata["classes"][7]["calibrationState"], "val_f1_calibrated")
            self.assertEqual(metadata["classes"][7]["thresholdSource"], "val:val-f1")
            self.assertEqual(len(metadata["thresholdsSha256"]), 64)
            self.assertEqual(metadata["thresholdsSha256"], response.json()["thresholdsSha256"])
            self.assertNotIn(str(self.path), json.dumps(metadata))
            self.assertTrue(metadata["capabilities"]["requiresHumanReview"])
            response = client.post("/v1/detect", headers=HEADERS, json={"imageData": jpeg(), "confidence": 0.75})
            self.assertEqual(recorded[-1], 0.25)
            self.assertEqual([box["confidence"] for box in response.json()["boxes"]], [0.8])


if __name__ == "__main__":
    unittest.main()
