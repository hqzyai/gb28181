"""Small, dependency-free helpers shared by PPE audit, training and evaluation."""
import hashlib
import json
import math
from datetime import datetime, timezone
from pathlib import Path

LABELS = ["helmet", "gloves", "vest", "boots", "goggles", "none", "Person",
          "no_helmet", "no_goggle", "no_gloves", "no_boots"]


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False,
                                    default=str) + "\n", encoding="utf-8")
    temporary.replace(path)


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def verify_manifest(path, expected_sha256):
    """Verify both the manifest and immutable source bytes before GPU work."""
    path = Path(path)
    if sha256(path) != expected_sha256:
        raise RuntimeError("Dataset manifest changed after audit: " + str(path))
    records = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    for record in records:
        for field in ("image", "label"):
            source = Path(record[field])
            if not source.is_file():
                raise RuntimeError("Audited source is unavailable; rerun audit under the current --root: " + str(source))
            if sha256(source) != record[field + "_sha256"]:
                raise RuntimeError("Audited source bytes changed: " + str(source))
    return records


def wilson(successes, total, z=1.959963984540054):
    if not total:
        return [None, None]
    p = successes / total
    denominator = 1 + z * z / total
    center = (p + z * z / (2 * total)) / denominator
    half = z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total)) / denominator
    return [max(0.0, center - half), min(1.0, center + half)]


def box_iou(a, b):
    intersection = max(0.0, min(a[2], b[2]) - max(a[0], b[0])) * max(
        0.0, min(a[3], b[3]) - max(a[1], b[1]))
    area_a = max(0.0, a[2] - a[0]) * max(0.0, a[3] - a[1])
    area_b = max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1])
    union = area_a + area_b - intersection
    return intersection / union if union else 0.0


def match_detections(ground_truth, detections, threshold, iou_threshold=0.5):
    """One-class, confidence-ordered, one-to-one IoU matching; never computes AP."""
    predictions = sorted((p for p in detections if p["confidence"] >= threshold),
                         key=lambda p: p["confidence"], reverse=True)
    available = set(range(len(ground_truth)))
    true_positives = 0
    for prediction in predictions:
        matches = [(box_iou(prediction["xyxy"], ground_truth[i]), i) for i in available]
        if not matches:
            continue
        overlap, index = max(matches)
        if overlap >= iou_threshold:
            available.remove(index)
            true_positives += 1
    return true_positives, len(predictions) - true_positives, len(available)
