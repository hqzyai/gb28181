#!/usr/bin/env python3
"""Safely unpack and audit an immutable Construction-PPE dataset archive."""
import argparse
import collections
import json
import math
import os
import shutil
import stat
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

from common import LABELS, read_json, sha256, utc_now, write_json

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp", ".tif", ".tiff"}
SPLIT_NAMES = {"train": "train", "val": "val", "valid": "val", "test": "test"}


def extract_archive(archive, destination, max_bytes=5 * 1024 ** 3, max_files=20000):
    archive, destination = Path(archive).resolve(), Path(destination).resolve()
    digest = sha256(archive)
    marker = destination / "archive-provenance.json"
    if destination.exists():
        if marker.exists() and read_json(marker).get("archive_sha256") == digest:
            return digest
        raise ValueError("Extraction destination exists without the same immutable archive provenance")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".ppe-unpack-", dir=destination.parent))
    try:
        with zipfile.ZipFile(archive) as source:
            members = source.infolist()
            if len(members) > max_files or sum(m.file_size for m in members) > max_bytes:
                raise ValueError("Archive exceeds extraction size/file budget")
            seen = set()
            for member in members:
                normalized = member.filename.replace("\\", "/")
                path = PurePosixPath(normalized)
                mode = member.external_attr >> 16
                if (path.is_absolute() or ".." in path.parts or not path.parts or
                        ":" in path.parts[0] or stat.S_ISLNK(mode) or member.flag_bits & 1):
                    raise ValueError("Unsafe archive member: " + member.filename)
                if str(path) in seen:
                    raise ValueError("Duplicate archive pathname: " + member.filename)
                seen.add(str(path))
                target = temporary.joinpath(*path.parts)
                if temporary not in target.resolve().parents:
                    raise ValueError("Archive path escapes extraction root")
                if member.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with source.open(member) as input_file, target.open("wb") as output:
                        shutil.copyfileobj(input_file, output, 1024 * 1024)
            write_json(temporary / "archive-provenance.json", {
                "archive_sha256": digest, "archive_name": archive.name,
                "extracted_at": utc_now(), "archive_members": len(members)})
        temporary.rename(destination)
    except BaseException:
        shutil.rmtree(temporary)
        raise
    return digest


def identify_image(path, root):
    parts = path.relative_to(root).parts
    indices = [i for i, part in enumerate(parts[:-1]) if part.lower() == "images"]
    for index in indices:
        # Supports both images/train/file.jpg and train/images/file.jpg.
        if index + 1 < len(parts) - 1 and parts[index + 1].lower() in SPLIT_NAMES:
            split = SPLIT_NAMES[parts[index + 1].lower()]
        elif index and parts[index - 1].lower() in SPLIT_NAMES:
            split = SPLIT_NAMES[parts[index - 1].lower()]
        else:
            continue
        label_parts = list(parts)
        label_parts[index] = "labels"
        return split, root.joinpath(*label_parts).with_suffix(".txt")
    return None, None


def parse_label(path):
    objects, errors = [], []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        fields = line.split()
        try:
            if len(fields) != 5:
                raise ValueError("detection labels require exactly five fields")
            values = list(map(float, fields))
            class_id, x, y, width, height = values
            if not all(math.isfinite(value) for value in values):
                raise ValueError("non-finite label value")
            if class_id != int(class_id) or not 0 <= class_id < len(LABELS):
                raise ValueError("class ID outside canonical 11-class label map")
            if not (0 <= x <= 1 and 0 <= y <= 1 and 0 < width <= 1 and 0 < height <= 1):
                raise ValueError("invalid normalized center/size")
            tolerance = 1e-5
            if (x - width / 2 < -tolerance or y - height / 2 < -tolerance or
                    x + width / 2 > 1 + tolerance or y + height / 2 > 1 + tolerance):
                raise ValueError("bounding box extends beyond image")
            objects.append({"class_id": int(class_id), "xywhn": [x, y, width, height]})
        except (ValueError, OverflowError) as error:
            errors.append({"label": str(path), "line": line_number,
                           "value": line, "reason": str(error)})
    return objects, errors


def audit(root, archive, verify_images=True):
    root = Path(root).resolve()
    reports = root / "reports"
    reports.mkdir(parents=True, exist_ok=True)
    unpacked = root / "datasets" / "construction-ppe-v1.0.0" / "original"
    try:
        archive_digest = extract_archive(archive, unpacked)
    except Exception as error:
        write_json(reports / "audit.json", {"schema_version": 1, "created_at": utc_now(),
                   "status": "failed", "errors": [{"reason": str(error)}]})
        raise
    errors, warnings, records = [], [], []
    matched_labels = set()
    if verify_images:
        from PIL import Image
    for image in sorted(unpacked.rglob("*")):
        if not image.is_file() or image.suffix.lower() not in IMAGE_EXTENSIONS:
            continue
        split, label = identify_image(image, unpacked)
        if split is None:
            errors.append({"image": str(image), "reason": "image cannot be assigned to train/val/test"})
            continue
        size = None
        if verify_images:
            try:
                with Image.open(image) as opened:
                    size = list(opened.size)
                    opened.verify()
                if min(size) <= 0:
                    raise ValueError("image dimensions must be positive")
            except Exception as error:
                errors.append({"image": str(image), "reason": "invalid image: " + str(error)})
        objects = []
        if not label.is_file():
            errors.append({"image": str(image), "label": str(label),
                           "reason": "missing label; negative images require an explicit empty label"})
        else:
            matched_labels.add(label)
            try:
                objects, label_errors = parse_label(label)
                errors.extend(label_errors)
            except Exception as error:
                errors.append({"label": str(label), "reason": str(error)})
        records.append({"image": str(image), "label": str(label), "split": split,
                        "image_sha256": sha256(image),
                        "label_sha256": sha256(label) if label.is_file() else None,
                        "size": size, "objects": objects, "empty_label": label.is_file() and not objects})
    for label in unpacked.rglob("*.txt"):
        if "labels" not in label.relative_to(unpacked).parts or label in matched_labels:
            continue
        try:
            _, label_errors = parse_label(label)
            errors.extend(label_errors)
        except Exception as error:
            errors.append({"label": str(label), "reason": str(error)})
        warnings.append({"label": str(label), "reason": "orphan annotation has no matched image"})
    hashes = collections.defaultdict(list)
    for record in records:
        hashes[record["image_sha256"]].append(record)
    duplicate_groups, exclusions = [], []
    excluded_paths = set()
    for digest, group in hashes.items():
        if len(group) < 2:
            continue
        duplicate_groups.append({"sha256": digest, "images": [r["image"] for r in group],
                                 "splits": [r["split"] for r in group],
                                 "label_hashes": [r["label_sha256"] for r in group]})
        highest = max({"train": 0, "val": 1, "test": 2}[r["split"]] for r in group)
        survivors = [r for r in group if {"train": 0, "val": 1, "test": 2}[r["split"]] == highest]
        for record in group:
            priority = {"train": 0, "val": 1, "test": 2}[record["split"]]
            if priority < highest or (record["split"] == "train" and record != survivors[0]):
                excluded_paths.add(record["image"])
                exclusions.append({"image": record["image"], "split": record["split"],
                                   "sha256": digest, "reason": "exact duplicate; preserve held-out split",
                                   "preserved_images": [r["image"] for r in survivors]})
        if len({r["label_sha256"] for r in group}) > 1:
            warnings.append({"sha256": digest, "reason": "identical image with different label files"})
        if highest == 2 and len(survivors) > 1:
            warnings.append({"sha256": digest, "reason": "within-test duplicates preserved; support not independent"})
    effective = [r for r in records if r["image"] not in excluded_paths]
    for split in ("train", "val", "test"):
        if not any(r["split"] == split for r in effective):
            errors.append({"split": split, "reason": "required effective split is empty"})
    dataset = unpacked.parent
    for name, rows in (("manifest.jsonl", records), ("derived-manifest.jsonl", effective)):
        (dataset / name).write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows),
                                   encoding="utf-8")
    counts = {}
    for split in ("train", "val", "test"):
        original = [r for r in records if r["split"] == split]
        kept = [r for r in effective if r["split"] == split]
        counts[split] = {"original_images": len(original), "effective_images": len(kept),
                         "empty_labels": sum(r["empty_label"] for r in kept),
                         "classes": [{"id": i, "name": name,
                                      "images": sum(any(o["class_id"] == i for o in r["objects"]) for r in kept),
                                      "instances": sum(o["class_id"] == i for r in kept for o in r["objects"])}
                                     for i, name in enumerate(LABELS)]}
        expected = {"train": 1132, "val": 143, "test": 141}[split]
        if len(original) != expected:
            warnings.append({"split": split, "reason": "image count differs from official published count",
                             "expected": expected, "actual": len(original)})
        (dataset / (split + ".txt")).write_text("".join(r["image"] + "\n" for r in kept), encoding="utf-8")
    # JSON is a YAML subset, avoiding hand-escaped absolute paths in generated data.yaml.
    write_json(dataset / "data.yaml", {"path": str(dataset), "train": str(dataset / "train.txt"),
               "val": str(dataset / "val.txt"), "test": str(dataset / "test.txt"), "names": LABELS})
    write_json(dataset / "class-counts.json", counts)
    write_json(dataset / "exclusions.json", exclusions)
    report = {"schema_version": 1, "created_at": utc_now(), "status": "failed" if errors else "passed",
              "archive_sha256": archive_digest, "archive": str(Path(archive).resolve()),
              "canonical_classes": LABELS, "counts": counts, "errors": errors, "warnings": warnings,
              "exact_duplicate_groups": duplicate_groups, "exclusions": exclusions,
              "policy": "Original files immutable; test unchanged; remove lower-priority train/val exact duplicates",
              "expected_official_images": {"train": 1132, "val": 143, "test": 141},
              "near_duplicate_limit": "SHA256 covers exact bytes only; pHash/manual sequence audit remains pending",
              "data_yaml": str(dataset / "data.yaml"),
              "manifest_sha256": sha256(dataset / "manifest.jsonl"),
              "derived_manifest_sha256": sha256(dataset / "derived-manifest.jsonl")}
    write_json(reports / "audit.json", report)
    write_json(dataset / "audit.json", report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--archive", type=Path, required=True)
    args = parser.parse_args()
    report = audit(args.root, args.archive)
    print(json.dumps({"status": report["status"], "counts": report["counts"],
                      "errors": len(report["errors"]), "report": str(args.root / "reports/audit.json")}))
    if report["errors"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
