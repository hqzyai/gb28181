#!/usr/bin/env python3
"""Budgeted, reproducible YOLO26s PPE smoke/full training; no host service changes."""
import argparse
import csv
import fcntl
import json
import os
import platform
import shutil
import sys
from pathlib import Path

from common import LABELS, read_json, sha256, utc_now, verify_manifest, write_json


def acquire_lock(root):
    handle = (root / "training.lock").open("a+")
    try:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise RuntimeError("Another PPE training process owns training.lock")
    handle.seek(0)
    handle.truncate()
    handle.write(json.dumps({"pid": os.getpid(), "started_at": utc_now()}))
    handle.flush()
    return handle


def preflight(root, allocator_gib):
    # Set before the first CUDA/cuBLAS operation, including the compatibility probe.
    os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
    import torch
    import torchvision
    import ultralytics

    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is unavailable: refusing to silently train on CPU")
    if torch.cuda.device_count() != 1:
        raise RuntimeError("Expose exactly one GPU with CUDA_VISIBLE_DEVICES / container GPU device selection")
    capability = torch.cuda.get_device_capability(0)
    if capability != (12, 0):
        raise RuntimeError("Expected current server's Blackwell SM12.0, got " + str(capability))
    total_bytes = torch.cuda.get_device_properties(0).total_memory
    budget_bytes = int(allocator_gib * 1024 ** 3)
    if budget_bytes >= total_bytes:
        raise RuntimeError("Allocator budget must be less than visible GPU total memory")
    torch.cuda.set_per_process_memory_fraction(budget_bytes / total_bytes, device=0)
    torch.manual_seed(20261012)
    matrix = torch.randn((64, 64), device="cuda:0")
    product = matrix @ matrix.T
    if not torch.isfinite(product).all().item():
        raise RuntimeError("CUDA matrix multiplication produced non-finite output")
    boxes = torch.tensor([[0, 0, 10, 10], [0, 0, 10, 10]], dtype=torch.float32, device="cuda:0")
    scores = torch.tensor([0.9, 0.8], device="cuda:0")
    if torchvision.ops.nms(boxes, scores, 0.5).cpu().tolist() != [0]:
        raise RuntimeError("Torchvision CUDA NMS smoke failed")
    torch.cuda.synchronize()
    result = {"status": "passed", "checked_at": utc_now(), "python": platform.python_version(),
              "torch": torch.__version__, "torchvision": torchvision.__version__,
              "ultralytics": ultralytics.__version__, "cuda_runtime": torch.version.cuda,
              "device_name": torch.cuda.get_device_name(0), "capability": list(capability),
              "gpu_total_bytes": total_bytes, "allocator_budget_bytes": budget_bytes,
              "allocator_fraction": budget_bytes / total_bytes,
              "budget_scope": "PyTorch caching allocator only; external NVML guard limits total process GPU memory",
              "cuda_matmul": True, "cuda_torchvision_nms": True}
    write_json(root / "reports" / "gpu-preflight.json", result)
    del matrix, product, boxes, scores
    torch.cuda.empty_cache()
    return result


def gpu_usage():
    import torch
    torch.cuda.synchronize()
    return {"allocated_bytes": torch.cuda.memory_allocated(0),
            "reserved_bytes": torch.cuda.memory_reserved(0),
            "peak_allocated_bytes": torch.cuda.max_memory_allocated(0),
            "peak_reserved_bytes": torch.cuda.max_memory_reserved(0)}


def actual_epochs(csv_path):
    if not csv_path.exists():
        return None
    with csv_path.open(newline="", encoding="utf-8") as source:
        rows = [{k.strip(): v for k, v in row.items()} for row in csv.DictReader(source)]
    return int(float(rows[-1]["epoch"])) if rows else None


def run_training(args):
    import torch
    from ultralytics import YOLO

    root = args.root.resolve()
    root.mkdir(parents=True, exist_ok=True)
    lock = acquire_lock(root)
    audit_path = root / "reports" / "audit.json"
    audit = read_json(audit_path)
    if audit.get("status") != "passed":
        raise RuntimeError("Dataset audit did not pass; refusing training")
    data_yaml = Path(audit["data_yaml"])
    if not data_yaml.is_file():
        raise RuntimeError("data.yaml is unavailable; rerun audit with the container's --root /work")
    verify_manifest(data_yaml.parent / "derived-manifest.jsonl", audit["derived_manifest_sha256"])
    base = args.weights.resolve()
    if not base.is_file():
        raise RuntimeError("Explicit base weight file is missing; no floating automatic download")
    software = preflight(root, args.allocator_gib)
    runs = root / "runs"
    params = {"data": str(data_yaml), "imgsz": 640, "batch": args.batch, "device": 0,
              "epochs": args.epochs, "patience": 30, "workers": 2, "optimizer": "AdamW",
              "lr0": 0.001, "lrf": 0.01, "weight_decay": 0.0005, "cos_lr": True,
              "warmup_epochs": 3.0, "close_mosaic": 10, "seed": 20261012,
              "deterministic": True, "amp": True, "compile": False, "cache": False,
              "nms": False, "rect": False, "val": True, "plots": True,
              "save_period": 10, "project": str(runs), "exist_ok": False,
              "name": args.run_id}
    if args.mode in ("smoke", "all"):
        smoke_name = args.run_id + "-smoke"
        if (runs / smoke_name).exists():
            raise RuntimeError("Smoke output exists; select a new --run-id for traceable execution")
        probe = YOLO(str(base))
        probe_params = {**params, "name": smoke_name, "epochs": 1, "patience": 0,
                        "fraction": 0.03, "close_mosaic": 0, "plots": False, "save_period": -1}
        torch.cuda.reset_peak_memory_stats(0)
        probe.train(**probe_params)
        write_json(root / "reports" / (args.run_id + "-smoke.json"), {
            "status": "passed", "completed_at": utc_now(), "purpose": "CUDA/AMP/backprop/memory compatibility only",
            "quality_acceptance": False, "params": probe_params, "software": software,
            "actual_batch": probe.trainer.batch_size,
            "memory": gpu_usage(), "base_sha256": sha256(base)})
        del probe
        torch.cuda.empty_cache()
    if args.mode == "smoke":
        print(json.dumps({"status": "smoke_passed", "quality_acceptance": False}))
        return
    if (runs / args.run_id).exists():
        raise RuntimeError("Training output exists; select a new --run-id instead of overwriting a prior run")
    release = root / "models" / "releases" / args.run_id
    if release.exists():
        raise RuntimeError("Model release exists; immutable releases must use a new --run-id")
    torch.cuda.reset_peak_memory_stats(0)
    model = YOLO(str(base))  # Always reinitialize from base, never continue smoke training.
    model.train(**params)
    trainer = model.trainer
    run_dir = Path(trainer.save_dir)
    best = run_dir / "weights" / "best.pt"
    if not best.is_file():
        raise RuntimeError("Training finished without best.pt")
    candidate = YOLO(str(best))
    actual_labels = [candidate.names[i] for i in range(len(candidate.names))]
    if actual_labels != LABELS:
        raise RuntimeError("Candidate labels differ from audited canonical class order")
    release.mkdir(parents=True, exist_ok=False)
    shutil.copy2(best, release / "best.pt")
    for name in ("args.yaml", "results.csv"):
        if (run_dir / name).exists():
            shutil.copy2(run_dir / name, release / name)
    manifest = {"schema_version": 1, "created_at": utc_now(), "run_id": args.run_id,
                "state": "candidate_validation", "production_accepted": False, "task": "detect",
                "architecture": "YOLO26s", "classes": LABELS, "model_sha256": sha256(release / "best.pt"),
                "base_weights": str(base), "base_sha256": sha256(base),
                "dataset_archive_sha256": audit["archive_sha256"],
                "dataset_manifest_sha256": audit["derived_manifest_sha256"],
                "dataset_audit_sha256": sha256(audit_path), "software": software,
                "requested_parameters": params, "actual_parameters": vars(trainer.args),
                "actual_batch": trainer.batch_size, "actual_epochs": actual_epochs(run_dir / "results.csv"),
                "head": "one-to-one NMS-free", "memory": gpu_usage(), "training_directory": str(run_dir),
                "license": "Ultralytics and official Construction-PPE sources: AGPL-3.0; project must retain license terms",
                "limitations": ["No no_vest class", "Public-dataset candidate; office camera is link verification only",
                                "PPE alarm precision/recall on a construction site is not validated"]}
    write_json(release / "model-manifest.json", manifest)
    write_json(release / "labels.json", {str(i): name for i, name in enumerate(LABELS)})
    write_json(root / "reports" / (args.run_id + "-training.json"), manifest)
    print(json.dumps({"status": "trained_candidate", "release": str(release),
                      "actual_epochs": manifest["actual_epochs"], "sha256": manifest["model_sha256"]}))
    # Keep lock alive for the entire function; fcntl releases it on process exit.
    lock.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--run-id", default="ppe-yolo26s-20261012-r1")
    parser.add_argument("--mode", choices=("smoke", "train", "all"), default="all")
    parser.add_argument("--batch", type=int, choices=(1, 2, 4, 8, 16), default=4)
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--allocator-gib", type=float, default=7.0)
    args = parser.parse_args()
    if not 0 < args.allocator_gib <= 7 or not 1 <= args.epochs <= 100:
        parser.error("This shared-GPU validation permits <=7 GiB allocator and 1..100 epochs")
    try:
        run_training(args)
    except BaseException as error:
        write_json(args.root / "reports" / (args.run_id + "-training-failure.json"), {
            "status": "failed", "at": utc_now(), "error_type": type(error).__name__, "reason": str(error)})
        raise


if __name__ == "__main__":
    main()
