"""The exact Construction-PPE checkpoint class order, never COCO labels."""

CLASS_NAMES = (
    "helmet", "gloves", "vest", "boots", "goggles", "none", "Person",
    "no_helmet", "no_goggle", "no_gloves", "no_boots",
)
LABELS = (
    "安全帽", "手套", "反光背心", "安全靴", "护目镜", "未定义装备状态", "人员",
    "未戴安全帽", "未戴护目镜", "未戴手套", "未穿安全靴",
)


def class_metadata(class_id: int) -> dict:
    code = CLASS_NAMES[class_id]
    kind = "risk" if code.startswith("no_") else "unknown" if code == "none" else "object"
    return {
        "classId": class_id,
        "classCode": code,
        "label": LABELS[class_id],
        "kind": kind,
        "riskType": LABELS[class_id] if kind == "risk" else None,
    }


def all_classes() -> list[dict]:
    return [class_metadata(index) for index in range(len(CLASS_NAMES))]
