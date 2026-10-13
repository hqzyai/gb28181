"""Meaningful dependency-free checks; no Torch installation or GPU allocation."""
import json
import stat
import tempfile
from types import SimpleNamespace
import unittest
import zipfile
from pathlib import Path

from audit_dataset import audit, extract_archive, parse_label
from common import match_detections, sha256, verify_manifest, wilson
from evaluate import choose_thresholds, complete_counts, predict_records


class LogicTests(unittest.TestCase):
    def test_archive_traversal_and_symlinks(self):
        for name, mode in (("../../escape", 0), ("/absolute", 0),
                           ("C:\\escape", 0), ("link", stat.S_IFLNK | 0o777)):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                path = Path(directory)
                with zipfile.ZipFile(path / "bad.zip", "w") as archive:
                    entry = zipfile.ZipInfo(name)
                    entry.external_attr = mode << 16
                    archive.writestr(entry, b"payload")
                with self.assertRaises(ValueError):
                    extract_archive(path / "bad.zip", path / "output")
                self.assertFalse((path / "output").exists())

    def test_duplicate_exclusion_preserves_original_test(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with zipfile.ZipFile(root / "data.zip", "w") as archive:
                for split, name, data in (("train", "overlap", b"same-test-image"),
                                          ("train", "unique", b"train-image"),
                                          ("val", "unique", b"val-image"),
                                          ("test", "overlap", b"same-test-image")):
                    archive.writestr("images/" + split + "/" + name + ".jpg", data)
                    archive.writestr("labels/" + split + "/" + name + ".txt", "0 0.5 0.5 0.2 0.2\n")
            report = audit(root, root / "data.zip", verify_images=False)
            self.assertEqual(report["status"], "passed")
            self.assertEqual(report["counts"]["train"]["original_images"], 2)
            self.assertEqual(report["counts"]["train"]["effective_images"], 1)
            self.assertEqual(report["counts"]["test"]["original_images"], 1)
            self.assertEqual(report["counts"]["test"]["effective_images"], 1)
            self.assertEqual(len(report["exclusions"]), 1)
            self.assertEqual(report["exclusions"][0]["split"], "train")
            self.assertTrue(Path(report["exclusions"][0]["image"]).exists())
            self.assertTrue(Path(report["data_yaml"]).exists())
            # An idempotent audit reuses immutable archive extraction.
            second = audit(root, root / "data.zip", verify_images=False)
            self.assertEqual(second["derived_manifest_sha256"], report["derived_manifest_sha256"])

    def test_labels_invalid_and_empty_negative(self):
        with tempfile.TemporaryDirectory() as directory:
            label = Path(directory) / "sample.txt"
            label.write_text("11 0.5 0.5 0.2 0.2\n0 nan 0.5 0.2 0.2\n0 0.9 0.5 0.4 0.2\n")
            objects, errors = parse_label(label)
            self.assertEqual(len(errors), 3)
            self.assertFalse(objects)
            label.write_text("")
            self.assertEqual(parse_label(label), ([], []))

    def test_manifest_detects_source_mutation_and_host_only_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image, label = root / "a.jpg", root / "a.txt"
            image.write_bytes(b"source-image")
            label.write_text("0 0.5 0.5 0.2 0.2\n")
            manifest = root / "derived-manifest.jsonl"
            manifest.write_text(json.dumps({"image": str(image), "label": str(label),
                "image_sha256": sha256(image), "label_sha256": sha256(label)}) + "\n")
            expected = sha256(manifest)
            self.assertEqual(len(verify_manifest(manifest, expected)), 1)
            image.write_bytes(b"mutated-image")
            with self.assertRaisesRegex(RuntimeError, "source bytes changed"):
                verify_manifest(manifest, expected)
            image.unlink()
            with self.assertRaisesRegex(RuntimeError, "rerun audit"):
                verify_manifest(manifest, expected)

    def test_one_to_one_match_counts_duplicate_predictions_as_false_positive(self):
        gt = [[0, 0, 10, 10]]
        detections = [{"xyxy": [0, 0, 10, 10], "confidence": 0.9},
                      {"xyxy": [0, 0, 10, 10], "confidence": 0.8},
                      {"xyxy": [20, 20, 30, 30], "confidence": 0.2}]
        self.assertEqual(match_detections(gt, detections, 0.35), (1, 1, 0))
        self.assertEqual(match_detections(gt, detections, 0.85), (1, 0, 0))
        self.assertEqual(match_detections(gt, [], 0.35), (0, 0, 1))

    def test_small_sample_wilson_and_undefined_precision(self):
        low, high = wilson(4, 4)
        self.assertAlmostEqual(low, 0.510109, places=5)
        self.assertAlmostEqual(high, 1)
        self.assertEqual(wilson(0, 0), [None, None])
        counts = complete_counts({"tp": 0, "fp": 0, "fn": 4})
        self.assertIsNone(counts["precision"])
        self.assertEqual(counts["recall"], 0)

    def test_threshold_calibration_uses_supplied_validation_only_and_completes_all_classes(self):
        records = [{"image": "/val.jpg", "size": [100, 100],
                    "objects": [{"class_id": 0, "xywhn": [0.5, 0.5, 0.2, 0.2]}]}]
        predictions = {"/val.jpg": [{"class_id": 0, "confidence": 0.6, "xyxy": [40, 40, 60, 60]}]}
        thresholds = choose_thresholds(records, predictions, "val-f1")
        self.assertEqual(len(thresholds), 11)
        self.assertEqual(thresholds[0]["conf_threshold"], 0.6)
        self.assertEqual(thresholds[0]["calibration_state"], "val_f1_low_support")
        self.assertEqual(thresholds[7]["calibration_state"], "no_validation_support")
        baseline = choose_thresholds(records, predictions, "baseline")
        self.assertEqual(baseline[0]["conf_threshold"], 0.35)

    def test_predict_source_lists_are_physically_bounded_by_batch(self):
        class FakeModel:
            def __init__(self):
                self.calls = []

            def predict(self, **options):
                self.calls.append(options)
                return iter(SimpleNamespace(path=path, boxes=None, orig_shape=(100, 100))
                            for path in options["source"])

        with tempfile.TemporaryDirectory() as directory:
            records = [{"image": str(Path(directory).resolve() / (str(i) + ".jpg"))} for i in range(13)]
            model = FakeModel()
            predictions = predict_records(model, records, 4, 0, Path(directory) / "predictions.jsonl")
            self.assertEqual([len(call["source"]) for call in model.calls], [4, 4, 4, 1])
            self.assertEqual(set(predictions), {r["image"] for r in records})
            for call in model.calls:
                self.assertEqual(call["conf"], 0.001)
                self.assertFalse(call["nms"])
                self.assertFalse(call["rect"])
                self.assertFalse(call["augment"])


if __name__ == "__main__":
    unittest.main()
