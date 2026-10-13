#!/usr/bin/env python3
"""Official AP plus frozen-threshold counts; test is never used for calibration."""
import argparse
import csv
import json
import math
from pathlib import Path

from common import LABELS, match_detections, read_json, sha256, utc_now, verify_manifest, wilson, write_json


def read_records(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def ground_truth(record, class_id):
    width, height = record["size"]
    boxes = []
    for obj in record["objects"]:
        if obj["class_id"] == class_id:
            x, y, w, h = obj["xywhn"]
            boxes.append([(x - w / 2) * width, (y - h / 2) * height,
                          (x + w / 2) * width, (y + h / 2) * height])
    return boxes


def counts_for_class(records, predictions, class_id, threshold):
    true_positive = false_positive = false_negative = images_with_gt = 0
    for record in records:
        boxes = ground_truth(record, class_id)
        images_with_gt += bool(boxes)
        detected = [p for p in predictions[record["image"]] if p["class_id"] == class_id]
        tp, fp, fn = match_detections(boxes, detected, threshold, 0.5)
        true_positive += tp
        false_positive += fp
        false_negative += fn
    return {"n_images": images_with_gt, "n_images_total": len(records),
            "n_gt": true_positive + false_negative, "n_pred": true_positive + false_positive,
            "tp": true_positive, "fp": false_positive, "fn": false_negative}


def complete_counts(counts):
    tp, fp, fn = counts["tp"], counts["fp"], counts["fn"]
    precision = tp / (tp + fp) if tp + fp else None
    recall = tp / (tp + fn) if tp + fn else None
    precision_ci = wilson(tp, tp + fp)
    recall_ci = wilson(tp, tp + fn)
    return {**counts, "precision": precision, "recall": recall,
            "precision_ci_low": precision_ci[0], "precision_ci_high": precision_ci[1],
            "recall_ci_low": recall_ci[0], "recall_ci_high": recall_ci[1]}


def choose_thresholds(records, predictions, mode):
    result = []
    for class_id, name in enumerate(LABELS):
        support = sum(len(ground_truth(record, class_id)) for record in records)
        threshold, f1 = 0.35, None
        state = "baseline_uncalibrated"
        if mode == "val-f1" and support:
            candidates = []
            for index in range(1, 20):
                trial = round(index / 20, 2)
                counts = counts_for_class(records, predictions, class_id, trial)
                score = 2 * counts["tp"] / (2 * counts["tp"] + counts["fp"] + counts["fn"])
                candidates.append((score, trial))
            f1, selected = max(candidates)  # Equal F1 chooses the higher threshold.
            if f1 > 0:
                threshold = round(selected, 2)
                state = "val_f1_calibrated" if support >= 20 else "val_f1_low_support"
            else:
                state = "no_usable_validation_detections"
        elif not support:
            state = "no_validation_support"
        result.append({"class_id": class_id, "class_name": name, "conf_threshold": threshold,
                       "calibration_state": state, "validation_gt_instances": support,
                       "validation_f1": f1, "production_alarm_accepted": False,
                       "semantics": "unknown" if name == "none" else "original_dataset_label"})
    return result


def predict_records(model, records, batch, device, output):
    predictions = {}
    sources = [record["image"] for record in records]
    with Path(output).open("w", encoding="utf-8") as saved:
        # LoadPilAndNumpy treats a supplied image list as ONE batch, regardless of
        # batch=. Bound the source list itself so warmup and forward stay bounded.
        for offset in range(0, len(sources), batch):
            chunk = sources[offset:offset + batch]
            stream = model.predict(source=chunk, stream=True, imgsz=640, batch=batch, device=device,
                                   conf=0.001, nms=False, rect=False, compile=False, verbose=False,
                                   save=False, save_txt=False, augment=False)
            for result in stream:
                image = str(Path(result.path).resolve())
                boxes = result.boxes
                rows = []
                if boxes is not None:
                    for coordinates, confidence, class_id in zip(boxes.xyxy.cpu().tolist(),
                            boxes.conf.cpu().tolist(), boxes.cls.cpu().tolist()):
                        if not all(math.isfinite(value) for value in coordinates + [confidence]):
                            raise RuntimeError("Model returned a non-finite detection")
                        rows.append({"class_id": int(class_id), "confidence": float(confidence),
                                     "xyxy": coordinates})
                if image in predictions:
                    raise RuntimeError("Duplicate source image path in prediction stream")
                predictions[image] = rows
                saved.write(json.dumps({"image": image, "original_shape_hw": list(result.orig_shape),
                                        "detections": rows}, ensure_ascii=False) + "\n")
    if set(predictions) != set(sources):
        raise RuntimeError("Prediction stream did not cover the exact audited split")
    return predictions


def json_plain(value):
    if isinstance(value, dict):
        return {key: json_plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_plain(item) for item in value]
    if hasattr(value, "item"):
        return value.item()
    return value


def official_metrics(model, data, split, batch, device, output):
    # AP comes exclusively from Ultralytics' standard validator, not custom matching.
    metrics = model.val(data=str(data), split=split, imgsz=640, batch=batch, device=device,
                        conf=0.001, nms=False, rect=False, augment=False, compile=False,
                        plots=True, save_json=False, verbose=True,
                        project=str(output), name="official-" + split, exist_ok=False)
    summary = json_plain(metrics.summary(decimals=8))
    write_json(output / ("official-" + split + "-summary-original.json"), summary)
    write_json(output / ("official-" + split + "-confusion-matrix.json"), {
        "classes": LABELS + ["background"], "matrix": metrics.confusion_matrix.matrix.tolist(),
        "note": "Official validator matrix; raw AP scan conf=0.001, not operational threshold matrix"})
    return {row["Class"]: row for row in summary}, json_plain(metrics.results_dict), json_plain(metrics.speed)


def write_csv(path, rows):
    with Path(path).open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def evaluate(args):
    import torch
    import torchvision
    import ultralytics
    from ultralytics import YOLO

    root, release = args.root.resolve(), args.release.resolve()
    from train import acquire_lock
    lock = acquire_lock(root)
    audit = read_json(root / "reports" / "audit.json")
    if audit["status"] != "passed":
        raise RuntimeError("Dataset audit must pass before evaluation")
    manifest = read_json(release / "model-manifest.json")
    weight = release / "best.pt"
    if sha256(weight) != manifest["model_sha256"]:
        raise RuntimeError("Weight SHA256 differs from immutable release manifest")
    if manifest["dataset_manifest_sha256"] != audit["derived_manifest_sha256"]:
        raise RuntimeError("Dataset differs from training manifest")
    dataset = Path(audit["data_yaml"]).parent
    derived_manifest = dataset / "derived-manifest.jsonl"
    records = verify_manifest(derived_manifest, audit["derived_manifest_sha256"])
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA unavailable during evaluation")
    torch.cuda.set_per_process_memory_fraction(args.allocator_gib * 1024 ** 3 /
        torch.cuda.get_device_properties(0).total_memory, device=0)
    output = root / "reports" / (manifest["run_id"] + "-evaluation")
    output.mkdir(parents=True, exist_ok=False)
    model = YOLO(str(weight))
    if [model.names[i] for i in range(len(model.names))] != LABELS:
        raise RuntimeError("Weights do not use the canonical audited label order")
    val_records = [r for r in records if r["split"] == "val"]
    val_ap, val_summary, val_speed = official_metrics(model, dataset / "data.yaml", "val",
                                                     args.batch, 0, output)
    val_predictions = predict_records(model, val_records, args.batch, 0, output / "val-predictions.jsonl")
    thresholds_path = release / "thresholds.json"
    if thresholds_path.exists():
        thresholds = read_json(thresholds_path)
        if (thresholds.get("model_sha256") != manifest["model_sha256"] or
                thresholds.get("dataset_manifest_sha256") != audit["derived_manifest_sha256"] or
                thresholds.get("mode") != args.threshold_mode or
                thresholds.get("source_split") != "val"):
            raise RuntimeError("Existing threshold file cannot be changed using test results")
    else:
        thresholds = {"schema_version": 1, "created_at": utc_now(), "mode": args.threshold_mode,
                      "source_split": "val", "model_sha256": manifest["model_sha256"],
                      "dataset_manifest_sha256": audit["derived_manifest_sha256"],
                      "matching": "confidence-ordered one-to-one same-class IoU>=0.5",
                      "classes": choose_thresholds(val_records, val_predictions, args.threshold_mode),
                      "no_vest": "disabled_missing_training_class", "production_accepted": False}
        write_json(thresholds_path, thresholds)
    threshold_digest = sha256(thresholds_path)
    write_json(output / "thresholds-frozen.json", thresholds)
    # No threshold selection below this line, especially not from the held-out test split.
    test_records = [r for r in records if r["split"] == "test"]
    test_ap, test_summary, test_speed = official_metrics(model, dataset / "data.yaml", "test",
                                                        args.batch, 0, output)
    test_predictions = predict_records(model, test_records, args.batch, 0, output / "test-predictions.jsonl")
    split_reports = {}
    for split, selected, predictions, ap_rows, aggregate, speed in (
            ("val", val_records, val_predictions, val_ap, val_summary, val_speed),
            ("test", test_records, test_predictions, test_ap, test_summary, test_speed)):
        rows, official_rows = [], []
        for configuration in thresholds["classes"]:
            class_id, name = configuration["class_id"], configuration["class_name"]
            counts = complete_counts(counts_for_class(selected, predictions, class_id,
                                                     configuration["conf_threshold"]))
            official = ap_rows.get(name, {})
            row = {"dataset": "construction-ppe-v1.0.0-derived", "split": split,
                   "class_id": class_id, "class_name": name, **counts,
                   "AP50": float(official["mAP50"]) if official else None,
                   "AP50_95": float(official["mAP50-95"]) if official else None,
                   "conf_threshold": configuration["conf_threshold"], "match_iou": 0.5,
                   "status": "insufficient_support" if counts["n_gt"] < 20 else "public_dataset_only",
                   "calibration_state": configuration["calibration_state"]}
            rows.append(row)
            official_rows.append({"class_id": class_id, "class_name": name,
                "n_images": counts["n_images"], "n_gt": counts["n_gt"],
                "precision_at_max_mean_f1": float(official["Box-P"]) if official else None,
                "recall_at_max_mean_f1": float(official["Box-R"]) if official else None,
                "AP50": row["AP50"], "AP50_95": row["AP50_95"],
                "status": row["status"]})
        write_csv(output / ("operating-" + split + "-per-class.csv"), rows)
        write_csv(output / ("official-" + split + "-per-class.csv"), official_rows)
        split_reports[split] = {"n_images": len(selected), "per_class": rows,
                               "official_aggregate": aggregate, "official_speed_ms": speed}
    report = {"schema_version": 1, "completed_at": utc_now(), "run_id": manifest["run_id"],
              "model_sha256": manifest["model_sha256"], "dataset_manifest_sha256": audit["derived_manifest_sha256"],
              "thresholds_sha256": threshold_digest, "threshold_source": "val", "test_used_for_tuning": False,
              "software": {"torch": torch.__version__, "torchvision": torchvision.__version__,
                           "ultralytics": ultralytics.__version__, "cuda_runtime": torch.version.cuda},
              "evaluation": {"imgsz": 640, "batch": args.batch, "nms": False, "rect": False,
                             "AP_min_conf": 0.001, "match_iou": 0.5}, "splits": split_reports,
              "confidence_interval": "Wilson 95% conditional on independent objects; video correlations invalidate this assumption",
              "state": "candidate_validation", "production_accepted": False,
              "limitations": ["Official public dataset only; no labeled construction-site test data",
                              "Office camera can validate pipeline, not construction-site precision/recall",
                              "No no_vest training class; none has unspecified semantics",
                              "Exact duplicates audited; semantic/near duplicates need separate review"]}
    write_json(output / "evaluation.json", report)
    write_json(output / "acceptance.json", {"status": "candidate_validation", "production_accepted": False,
               "public_dataset_evaluation_completed": True, "field_accuracy_verified": False,
               "office_link_validation": "to_be_verified_by_deployment",
               "model_sha256": manifest["model_sha256"], "thresholds_sha256": threshold_digest})
    print(json.dumps({"status": "evaluated_candidate", "report": str(output / "evaluation.json"),
                      "production_accepted": False}))
    lock.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--release", type=Path, required=True)
    parser.add_argument("--batch", type=int, choices=(1, 2, 4), default=4)
    parser.add_argument("--threshold-mode", choices=("baseline", "val-f1"), default="val-f1")
    parser.add_argument("--allocator-gib", type=float, default=3.0)
    args = parser.parse_args()
    if not 0 < args.allocator_gib <= 7:
        parser.error("allocator budget must be >0 and <=7 GiB")
    try:
        evaluate(args)
    except BaseException as error:
        write_json(args.root / "reports" / (args.release.name + "-evaluation-failure.json"), {
            "status": "failed", "at": utc_now(), "error_type": type(error).__name__, "reason": str(error)})
        raise


if __name__ == "__main__":
    main()
