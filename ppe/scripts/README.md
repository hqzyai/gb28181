# PPE 数据审计、训练及评估脚本

服务器目录 `${PPE_WORK_ROOT}` 挂载为容器内 `/work`。脚本需要 Pillow、PyTorch、torchvision、Ultralytics；当前组合固定 Python 3.12、Torch 2.12.1/cu130、torchvision 0.27.1、Ultralytics 8.4.175。宿主 GPU 资源守卫由部署流程运行，脚本的 allocator 限制不能替代 NVML 对总进程显存的监控。

```bash
python /work/scripts/audit_dataset.py --root /work --archive /work/data/construction-ppe.zip
python /work/scripts/train.py --root /work --weights /work/models/yolo26s.pt --mode all
python /work/scripts/evaluate.py --root /work --release /work/models/releases/ppe-yolo26s-20261012-r1 --threshold-mode val-f1
```

`--mode all` 先执行独立 smoke（3% 训练图片、1 epoch，完整验证集），再重新加载原始 COCO 权重进行最多 100 epoch 正式训练。Smoke 只能证明 CUDA/反向传播/AMP/资源兼容。也可先 `--mode smoke` 再 `--mode train`；不要再次用 `all` 覆盖已有 smoke。默认 batch=4，允许显存预检失败后用新 run ID 降为 2 或 1；训练禁止自动选择 GPU 或占用共享 GPU 的多数显存。

原 ZIP 和解压图片不修改。实际文件数、类数、空标签、错误及所有图像 SHA256 保存在 `reports/audit.json` 和 `datasets/construction-ppe-v1.0.0`。异常图像、缺标签、错误类别/坐标中止训练。空标签可作为明确的负样本。精确重复优先保留 test、其次 val：train 与 val 的低优先级重复仅从派生清单排除，原始文件保留，test 不改。字节 SHA256 无法识别重新编码、裁剪或相邻帧的近重复；此限制写入报告。

训练结果保存在 `runs/<run-id>`，候选权重和 manifest 复制到 `models/releases/<run-id>`。软件版本、初始与候选权重哈希、数据清单哈希、真实轮数/批次、请求与实际参数、显存峰值均保留。训练和评估共用文件锁，不会在此脚本间并发占卡。默认输出目录拒绝覆盖；重跑使用新 run ID。

评估先运行 val 并冻结每类阈值，再读取 test。`val-f1` 在验证集 0.05..0.95 网格上选 F1 最佳阈值；低支持类别明确标记，无法检出的类别保留 0.35 基线并标记未校准。`baseline` 统一采用 0.35，明确标为未校准。已有阈值不能随 test 结果变更。AP 来自官方 Ultralytics validator（conf=0.001），固定阈值 TP/FP/FN 采用每类、置信度降序、一对一 IoU>=0.5 的匹配。所有 11 类均输出，缺样本 AP/recall 写 null。官方 summary 的 precision/recall 使用最大平均 F1 置信阈值，单独保存，不能当业务阈值结果。

所有阶段使用 YOLO26 NMS-free 头（nms=False）、640 图像尺寸，推理及评估 `rect=False`。官方混淆矩阵对应 AP 的低置信阈值，不是运营阈值混淆矩阵。运营阈值统计及 Wilson 95% 区间保存在 `operating-{val,test}-per-class.csv`。Wilson 假定独立对象，公共集近重复和现场连续视频会使区间乐观；实际工地验收需按摄像头、完整日期/片段隔离，并按独立事件或分组 bootstrap 评估。

交付状态始终为 `candidate_validation`，不声称工地精度验收通过。当前办公室摄像头仅证明取流、画框、历史和识别链路。`none` 的业务语义未明确，`no_vest` 未在训练类中，未穿背心告警保持禁用。真实施工精度、误报/摄像头小时、事件发现 p95 和至少 72 小时现场运行需另行验证。

本机轻量验证（无 Torch 安装/分配）：

```bash
python3 -m unittest discover -s scripts -p test_logic.py
python3 -m py_compile scripts/common.py scripts/audit_dataset.py scripts/train.py scripts/evaluate.py scripts/test_logic.py
```
