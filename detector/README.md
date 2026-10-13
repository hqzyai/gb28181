# Construction-PPE detector

这是 GB28181 平台的内部检测服务。摄像头取流、周期任务、告警复核、结果和截图存储由平台负责；服务只处理一张 JPEG 并返回 YOLO 框。`helmet/vest/Person` 等对象是中性检测框，四个 `no_*` 标签是待人工复核的 PPE 风险候选，`none` 为未知状态。模型不包含 `no_vest`，服务不根据没有背心框推断未穿背心。

## 运行条件

- Python 3.12；Ultralytics 8.4.175；Torch 2.12.1 / torchvision 0.27.1 / CUDA 13.0。Torch 的基础镜像由部署方传入固定 digest，Dockerfile 拒绝安装其他 Torch 版本。
- NVIDIA 容器运行时提供 GPU 0。GPU 不可用时服务明确未就绪，不回退 CPU 或 COCO 模型。
- `/models/best.pt` 为训练后的 Construction-PPE 权重，必须只读挂载。权重的 11 个类别必须与 `classes.py` 的顺序完全一致。
- Token 由 `DETECTOR_API_TOKEN` 或 `DETECTOR_API_TOKEN_FILE` 注入，至少 32 个字符。Token 不写入镜像、API 响应或访问日志。
- 不配置宿主机 `ports`，只允许同 Docker 网络的 WVP 访问 `http://detector:8080`。

```bash
docker build --build-arg TORCH_BASE_IMAGE='pytorch/pytorch@sha256:<已验证的digest>' -t hqzyai/gb28181-detector:<模型版本> .
```

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `DETECTOR_API_TOKEN` / `DETECTOR_API_TOKEN_FILE` | 必须提供 | 内部 Bearer 凭据 |
| `DETECTOR_MODEL_PATH` | `/models/best.pt` | 只读权重 |
| `DETECTOR_MODEL_NAME` | `construction-ppe-yolo26s` | 模型显示名称 |
| `DETECTOR_MODEL_VERSION` | 模型名 + SHA256 前 12 位 | 不可变发布版本 |
| `DETECTOR_MODEL_SHA256` | 空 | 建议部署时填训练权重 SHA256，错位文件拒绝加载 |
| `DETECTOR_THRESHOLDS_PATH` | 空 | 可选只读冻结阈值 JSON；配置后缺文件、SHA/类别错位或非法阈值会未就绪 |
| `DETECTOR_IMAGE_SIZE` | `640` | 推理张量尺寸，320..1280 且为 32 倍数 |
| `DETECTOR_CONFIDENCE` | `0.25` | 默认阈值，0.01..0.99 |
| `DETECTOR_TIMEOUT_SECONDS` | `15` | 单次服务推理等待时限 |
| `DETECTOR_RECOVERY_DELAY_SECONDS` | `30` | GPU 故障后的进程恢复延迟、超时后的硬恢复宽限期；5..300 秒 |
| `DETECTOR_GPU_MEMORY_MIB` | `2048` | PyTorch 单进程 CUDA allocator 预算；不包含 CUDA 上下文等额外开销 |

只启动一个 Uvicorn worker。单并发、无待处理图片队列；正在推理返回 429。超时返回 503，并保持 GPU 锁直到实际计算结束，避免不断堆积 CUDA 任务。超时任务未完成时 `readyz` 为 503、公开模型状态未就绪；任务完成后恢复就绪。运行期 OOM/底层推理异常会未就绪，自己的 watchdog 默认等待 30 秒后以 code 70 退出；超时任务在额外 30 秒宽限期后仍占用 GPU，则以 code 71 退出。部署必须设 `restart: unless-stopped`，由 Docker 拉起新进程并重新 warmup。watchdog 以任务 ticket 和生命周期锁核对，旧超时任务不会杀死已恢复的新任务；正常停止时取消 watchdog。重新启动 warmup 遇到 OOM 亦延迟退出重试，权重/阈值/类别配置错误则保持 503 等待修复，不制造配置错误重启循环。服务退出等待当前推理；容器应设置有限 `stop_grace_period`。镜像创建真实 UID/GID 1001 的 detector 用户，并把 TorchInductor 缓存放入可写 `/tmp/torchinductor`，避免未知 UID 引发 getpass/getpwuid 错误。

API 依赖固定 Click 8.3.1，与基础镜像继承的 `spin 0.18` 的 Click 约束兼容；Docker 构建对整个 `--system-site-packages` runtime 执行 `pip check`，失败就停止构建，保留锁文件和 GPU 版本断言。

输入校验直接使用 Pillow JPEG 解码器，绕过 Ultralytics 对 `PIL.Image.open` 的全局 HEIF 自动安装补丁；`YOLO_AUTOINSTALL=false` 禁止运行期间安装依赖。非法 JPEG 快速返回 400；意外解码异常返回 503 并释放并发锁，不会卡住后续有效帧。启动/恢复日志使用 Uvicorn 的日志处理器，可见 warmup 模型版本、SHA256 和失败原因；没有每帧日志。除 mock 契约测试，还通过真实 Uvicorn TCP 请求验证 401/400/415/413、解码异常后恢复及图片打开猴补丁回归。

Ultralytics predict 固定 `rect=False,nms=False` 使用 YOLO26 NMS-free 检测头，与训练验证一致。使用 `Results.boxes.xyxy` 的原图坐标，不重新实现 NMS。输入最大 3 MiB、最大边 4096 像素、最大总像素 8,388,608，接受 JPEG；图像先按 EXIF 转为正确显示方向再检测并返回该方向的尺寸。返回框不会以 640 张量坐标冒充原图坐标。

配置冻结阈值时，文件必须为 `schema_version: 1, source_split: val, mode: val-f1`，绑定已加载模型的 SHA256，包含正确的数据清单 SHA256、完整 11 类 ID/名称、有限的 `conf_threshold`（0.01..0.99）及 `calibration_state`。启动时冻结文件内容并公开其 SHA256；运行期间不会根据摄像头或 test 结果改变阈值。候选推理阈值使用请求阈值与所有冻结类阈值的最小值，再逐类使用 `max(请求阈值, 冻结类阈值)` 过滤，过滤前不四舍五入分数。请求阈值 0.01 时采用原始冻结 operating point。未配置文件时维持原来的请求/默认阈值行为。`/v1/model` 各类公开 `confidenceThreshold/calibrationState/thresholdSource`，模型和检测响应公开 `thresholdsSha256`；不公开文件路径。验证集选阈值与现场告警验收是不同证据，PPE 负类仍需人工复核。

## API

`GET /healthz`：200，`{"status":"alive"}`。

`GET /readyz`：权重校验、CUDA 加载、真实 warmup 全部成功才返回 200：

```json
{"status":"ready","engine":"yolo","modelVersion":"construction-ppe-yolo26s-<hash>","modelSha256":"<64位SHA256>"}
```

`GET /v1/model`：只返回名称、版本、SHA256、11 类列表、是否就绪和能力说明，没有文件路径、Token 或 GPU 明细。

`POST /v1/detect`：`Content-Type: application/json`，`Authorization: Bearer <token>`。字段：

```json
{
  "imageData": "<JPEG纯base64，亦支持data:image/jpeg;base64,前缀>",
  "frameId": "<可选，最长128字符>",
  "capturedAt": "2026-10-12T14:00:00+08:00",
  "confidence": 0.25
}
```

未传 `capturedAt` 时采用接收时间；传入时间必须有时区。未传 `frameId` 时生成 UUID。拒绝任意 URL、额外字段、非有限阈值、布尔或字符串阈值。成功结果示例（示例框不是实测证据）：

```json
{
  "frameId": "camera-1-0009",
  "capturedAt": "2026-10-12T14:00:00+08:00",
  "detectedAt": "2026-10-12T06:00:00.035+00:00",
  "imageWidth": 2560,
  "imageHeight": 1440,
  "engine": "yolo",
  "modelVersion": "construction-ppe-yolo26s-<hash>",
  "modelSha256": "<64位SHA256>",
  "inferenceMs": 35.0,
  "boxes": [
    {"classId": 7,"classCode": "no_helmet","label": "未戴安全帽","kind": "risk",
     "riskType": "未戴安全帽","confidence": 0.91,"x1": 20.0,"y1": 30.0,"x2": 110.0,"y2": 130.0}
  ]
}
```

无目标的真实推理可以成功返回 `boxes: []`。错误统一为 `{"error":{"code":"...","message":"..."}}`，不会回显图片。鉴权失败 401；非法 JSON/字段/图像/阈值 400；非 JSON 415；大图 413；繁忙 429（`Retry-After: 1`）；权重、CUDA、推理异常、OOM、超时 503。

## 契约测试

测试仅使用 Pillow、FastAPI、HTTPX 和 mock engine，不导入 Torch、不下载模型、不占 GPU：

```bash
python -m pip install -r requirements-test.txt
python -m unittest discover -s tests -v
```

API 依赖及其传递依赖均已锁版本。GPU runtime 的完整依赖快照保存在镜像 `/opt/detector/runtime-requirements.lock`，随最终镜像导出作为部署证据。服务镜像不包含权重、数据集或密钥；它们需要独立备份和校验，Docker save 本身不会导出只读模型挂载。

接口实现依据： [Ultralytics v8.4.175 参数](https://github.com/ultralytics/ultralytics/blob/v8.4.175/ultralytics/cfg/default.yaml)、[Ultralytics 原图框接口](https://docs.ultralytics.com/modes/predict/)、[FastAPI lifespan](https://fastapi.tiangolo.com/advanced/events/)、[Pillow 图像解码](https://pillow.readthedocs.io/en/stable/reference/Image.html)。许可证由依赖及 Construction-PPE/权重来源约束，部署包应一同保留相应源代码、许可证与模型来源清单。
