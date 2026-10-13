"""Load immutable validation-selected thresholds, without Torch or live calibration."""
from __future__ import annotations

import hashlib
import json
import math
from dataclasses import dataclass
from pathlib import Path

from .classes import CLASS_NAMES, all_classes
from .engine import EngineUnavailable


@dataclass(frozen=True)
class FrozenThresholds:
    sha256: str
    values: tuple[float, ...]
    states: tuple[str, ...]
    source: str

    def candidate_confidence(self, request_confidence: float) -> float:
        return min(request_confidence, min(self.values))

    def filter_boxes(self, boxes: list[dict], request_confidence: float) -> list[dict]:
        filtered = []
        for box in boxes:
            class_id = box.get("classId")
            if type(class_id) is not int or not 0 <= class_id < len(CLASS_NAMES) or box.get("classCode") != CLASS_NAMES[class_id]:
                raise EngineUnavailable("invalid_model_output", "模型类别与冻结阈值不匹配")
            score = box.get("confidence")
            if isinstance(score, bool) or not isinstance(score, (int, float)) or not math.isfinite(score) or not 0 <= score <= 1:
                raise EngineUnavailable("invalid_model_output", "模型检测置信度无效")
            if score >= max(self.values[class_id], request_confidence):
                filtered.append(box)
        return filtered

    def public_classes(self) -> list[dict]:
        return [{**entry, "confidenceThreshold": self.values[entry["classId"]],
                 "calibrationState": self.states[entry["classId"]], "thresholdSource": self.source}
                for entry in all_classes()]


def load_thresholds(path: str, model_sha256: str) -> FrozenThresholds | None:
    if not path:
        return None
    file = Path(path)
    if not file.is_file():
        raise EngineUnavailable("thresholds_missing", "冻结阈值文件未就绪")
    try:
        with file.open("rb") as stream:
            content = stream.read(128 * 1024 + 1)
        if len(content) > 128 * 1024:
            raise ValueError("threshold file exceeds size limit")
        data = json.loads(content)
        if (not isinstance(data, dict) or type(data.get("schema_version")) is not int or data["schema_version"] != 1
                or data.get("source_split") != "val" or data.get("mode") != "val-f1"):
            raise ValueError("unsupported frozen validation thresholds")
        if data.get("model_sha256") != model_sha256:
            raise EngineUnavailable("thresholds_hash_mismatch", "冻结阈值与模型权重校验和不匹配")
        manifest_digest = data.get("dataset_manifest_sha256")
        if not isinstance(manifest_digest, str) or len(manifest_digest) != 64 or any(c not in "0123456789abcdef" for c in manifest_digest):
            raise ValueError("invalid dataset manifest digest")
        classes = data.get("classes")
        if not isinstance(classes, list) or len(classes) != len(CLASS_NAMES):
            raise EngineUnavailable("thresholds_class_mismatch", "冻结阈值未包含完整 PPE 类别")
        values, states = {}, {}
        for item in classes:
            if not isinstance(item, dict):
                raise ValueError("invalid threshold entry")
            class_id = item.get("class_id")
            if (type(class_id) is not int or class_id in values or not 0 <= class_id < len(CLASS_NAMES)
                    or item.get("class_name") != CLASS_NAMES[class_id]):
                raise EngineUnavailable("thresholds_class_mismatch", "冻结阈值 PPE 类别 ID 或名称不匹配")
            threshold = item.get("conf_threshold")
            if (isinstance(threshold, bool) or not isinstance(threshold, (float, int))
                    or not math.isfinite(threshold) or not 0.01 <= threshold <= 0.99):
                raise ValueError("invalid confidence threshold")
            state = item.get("calibration_state")
            if not isinstance(state, str) or not state.strip() or len(state) > 96:
                raise ValueError("invalid calibration state")
            values[class_id], states[class_id] = float(threshold), state
        return FrozenThresholds(hashlib.sha256(content).hexdigest(),
                                tuple(values[index] for index in range(len(CLASS_NAMES))),
                                tuple(states[index] for index in range(len(CLASS_NAMES))), "val:val-f1")
    except EngineUnavailable:
        raise
    except (OSError, ValueError, TypeError, KeyError) as error:
        raise EngineUnavailable("thresholds_invalid", "冻结阈值文件格式或阈值无效") from error
