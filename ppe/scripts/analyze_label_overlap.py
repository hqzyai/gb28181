#!/usr/bin/env python3
"""Read-only GT geometry check: missing-PPE annotations versus Person boxes."""
import argparse
import json
import zipfile
from pathlib import Path, PurePosixPath

from common import LABELS, box_iou, sha256, utc_now, write_json

MISSING_IDS = (5, 7, 8, 9, 10)


def xyxy(box):
    x, y, width, height = box
    return [x - width / 2, y - height / 2, x + width / 2, y + height / 2]


def analyze(archive):
    result = {"schema_version": 1, "created_at": utc_now(), "archive_sha256": sha256(archive),
              "archive": str(Path(archive).resolve()), "classes": LABELS,
              "method": "For each negative/none GT, compare to every same-image Person GT; retain maximum normalized-box IoU. Exact means maximum coordinate difference <=1e-6.",
              "splits": {}, "orphan_labels_skipped": [],
              "limitations": ["GT geometry only, not assignment/loss/head ablation",
                              "Overlapping boxes can be valid multilabel annotations; overlap is not proof of model failure cause",
                              "No dataset, labels, model or threshold changed"]}
    with zipfile.ZipFile(archive) as zipped:
        names = set(zipped.namelist())
        image_stems = {str(PurePosixPath(name).with_suffix("")) for name in names
                       if name.startswith("images/") and PurePosixPath(name).suffix.lower() in
                       {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".tif", ".tiff"}}
        for split in ("train", "val", "test"):
            rows = {class_id: {"class_id": class_id, "class_name": LABELS[class_id], "instances": 0,
                    "images": 0, "images_without_person_gt": 0, "instances_without_person_gt": 0,
                    "exact_person_bbox": 0, "max_iou_ge_095": 0, "max_iou_ge_090": 0,
                    "max_iou_ge_050": 0, "max_iou_below_050": 0, "examples_exact": []}
                    for class_id in MISSING_IDS}
            count_images = 0
            all_instances = [0] * len(LABELS)
            for name in sorted(names):
                if not name.startswith("labels/" + split + "/") or not name.endswith(".txt"):
                    continue
                stem = str(PurePosixPath(name.replace("labels/", "images/", 1)).with_suffix(""))
                if stem not in image_stems:
                    result["orphan_labels_skipped"].append(name)
                    continue
                count_images += 1
                objects = []
                for line in zipped.read(name).decode().splitlines():
                    if not line.strip():
                        continue
                    values = list(map(float, line.split()))
                    if len(values) != 5 or values[0] != int(values[0]):
                        raise ValueError("Invalid label geometry: " + name)
                    class_id = int(values[0])
                    objects.append((class_id, values[1:]))
                    all_instances[class_id] += 1
                people = [box for class_id, box in objects if class_id == 6]
                for class_id, row in rows.items():
                    missing = [box for kind, box in objects if kind == class_id]
                    if not missing:
                        continue
                    row["images"] += 1
                    row["instances"] += len(missing)
                    if not people:
                        row["images_without_person_gt"] += 1
                    for box in missing:
                        if not people:
                            row["instances_without_person_gt"] += 1
                            continue
                        overlap = max(box_iou(xyxy(box), xyxy(person)) for person in people)
                        exact = any(max(abs(a - b) for a, b in zip(box, person)) <= 1e-6 for person in people)
                        row["exact_person_bbox"] += exact
                        row["max_iou_ge_095"] += overlap >= 0.95
                        row["max_iou_ge_090"] += overlap >= 0.90
                        row["max_iou_ge_050"] += overlap >= 0.50
                        row["max_iou_below_050"] += overlap < 0.50
                        if exact and len(row["examples_exact"]) < 5:
                            row["examples_exact"].append({"label": name, "negative_xywhn": box,
                                "Person_xywhn": next(person for person in people
                                    if max(abs(a - b) for a, b in zip(box, person)) <= 1e-6)})
            result["splits"][split] = {"images": count_images, "all_class_instances": {
                name: count for name, count in zip(LABELS, all_instances)}, "negative_vs_Person": list(rows.values())}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = analyze(args.archive)
    write_json(args.output, result)
    print(json.dumps({"report": str(args.output), "splits": {
        split: {row["class_name"]: {key: row[key] for key in ("instances", "exact_person_bbox", "max_iou_ge_095", "max_iou_ge_050")}
                for row in value["negative_vs_Person"]} for split, value in result["splits"].items()}}, indent=2))


if __name__ == "__main__":
    main()
