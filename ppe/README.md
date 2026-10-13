# PPE 训练与评估

`scripts/` 提供不可覆盖的审计、训练和独立评估流程；`gpu_guard.py` 监控共享 GPU，只允许停止明确指定的 `gb28181-ppe-*` 训练容器。数据、权重、训练产物和缓存不进入 Git。

当前已验证 Python 3.12、Torch 2.12.1/cu130、torchvision 0.27.1/cu130、Ultralytics 8.4.175 和 Blackwell SM 12.0。`train.py` 会明确拒绝不同的 CUDA/设备组合；其他硬件须先完成兼容性验证，不能跳过检查直接复用实测结论。

从仓库根目录构建固定训练环境：

```bash
docker build -f ppe/Dockerfile.train \
  --build-arg TORCH_IMAGE=pytorch/pytorch@sha256:72f863fa1fe13d5d87a72d00db2c85fb2d43409ee08dd26bc469de4c8a28b427 \
  -t gb28181-ppe-training:local ppe
```

选择新的 `PPE_WORK_ROOT`，将脚本复制到该目录的 `scripts/`，将官方 Construction-PPE ZIP 放入 `data/construction-ppe.zip`，官方 YOLO26s 初始化权重放入 `models/yolo26s.pt`。挂载到 `/work` 后先审计，再做 smoke 和正式训练，详见 [脚本说明](scripts/README.md)。准备好 UID/GID 1001 可写的工作目录、`USER/LOGNAME`、`YOLO_CONFIG_DIR` 和 `MPLCONFIGDIR`，并通过资源守卫预检。

离线训练还需事先准备 `yolo26n.pt`（AMP 校验）和 `Arial.ttf`（评估绘图）。将 AMP 权重放在 `/work/yolo26n.pt`；设置 `YOLO_CONFIG_DIR=/work/cache/ultralytics`，字体放在 `/work/cache/ultralytics/Ultralytics/Arial.ttf`。这两项不能依赖离线容器隐式下载。

每个训练 run 使用新名称。验证集冻结每类阈值后才评估 test，禁止根据已看过的 test 调阈值。正式权重、阈值与模型 manifest 必须成套发布；切换模型使用新版本目录，不能直接覆盖运行中的文件。

此次公开集候选完成 100 轮训练和独立 test 评估，但未通过未佩戴 PPE 自动告警验收。能力范围和实际指标见 [部署与验证说明](../doc/AI-PPE-DEPLOYMENT.md)。
