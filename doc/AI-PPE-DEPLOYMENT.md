# YOLO PPE 接入与验证

平台支持 `vision`、`yolo`、`hybrid` 三种识别引擎。YOLO 只调用内部检测服务，联合模式为同一抓拍原帧追加视觉模型分析并保留结果来源。框使用原图坐标，结果携带 frameId、抓拍时间、模型版本及权重 SHA256；视频播放和识别原帧分别展示。

YOLO 后台任务使用服务器抓拍，在浏览器关闭后继续运行。当前允许一个通道任务，每帧完成后按配置等待；任务定义保存在识别历史目录，后端启动时恢复。失败清除实时旧帧并自动重试，连续失败退避；手动停止删除持久任务定义。历史查看固定所选记录，后台继续检测，“返回实时结果”切回最近后台帧。

## 源码与构建

- `detector/`：单并发 GPU 检测服务、固定依赖、Dockerfile 和 HTTP/恢复测试。
- `ppe/`：训练环境、数据审计、正式训练、独立评估与共享 GPU 守卫。
- `docker/ppe/Dockerfile.wvp`、`Dockerfile.web`：分别包装构建出的 JAR 和前端静态资源。
- `docker/ppe/nginx.conf`、`backup.sh`：当前验证过的代理与备份模板。代理使用服务名 wvp/media 和媒体 HTTP 端口 28081；备份脚本与实际 `compose.yml` 放在同目录，要求 mysql 服务、wvp 数据库、文件密钥及 config/secrets/data 布局。按实际部署核对服务名和端口，备份输出包含私有数据，不进入 Git。

平台使用 Java 21 构建。前端使用 Node 20，在 `web/` 执行锁文件安装和 `npm run build:prod`；Webpack 4 在该 Node 版本下需要 `NODE_OPTIONS=--openssl-legacy-provider`。前端输出到 `src/main/resources/static/`，随后执行 Maven 构建。生成目录和打包文件不提交。

运行镜像构建时传入真实 `BUILD_REVISION` 和固定基础镜像 digest。已验证 Java runtime digest 为 `eclipse-temurin@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b`，Nginx digest 为 `nginx@sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236`。使用仓库根目录作为构建上下文；WVP Dockerfile 读取 `target/wvp-pro-*.jar`，Web Dockerfile 读取已构建的静态资源。

检测服务构建与接口契约见 [detector 说明](../detector/README.md)，训练步骤见 [训练说明](../ppe/README.md)。部署凭据、摄像头配置、现场截图、模型权重、数据集、数据库和 Docker 导出包由部署环境单独保存，不放入公开仓库。

## 平台配置

在实际使用的 Spring 外部配置中加入以下属性，通过私有环境文件或部署密钥注入凭据：

```yaml
ai-analysis:
  enabled: true
  yolo-enabled: true
  yolo-url: http://detector:8080
  yolo-token: ${AI_ANALYSIS_YOLO_TOKEN}
  yolo-confidence: 0.01
  yolo-timeout-seconds: 20
  default-engine: yolo
  task-interval-seconds: 2
  task-history-interval-seconds: 30
  history-directory: data/ai-history
```

冻结阈值模式下平台请求 floor 必须为 0.01，避免平台通用阈值截掉已经校准的候选。多模态配置 `api-url/api-key/model` 沿用现有接口；YOLO 单独使用时不要求外部视觉 API。

把 detector 加入 WVP 已使用的 Docker 网络，使用 `detector` 服务名，不映射宿主机端口。服务以 UID/GID 1001、只读根文件系统运行，只读挂载模型发布目录到 `/models`，为 `/tmp` 提供有限 tmpfs，移除 capabilities 并启用 no-new-privileges。设置 CPU、内存、PID 和显存预算，使用单 Uvicorn worker 与 `restart: unless-stopped`；这些约束在当前测试部署中已验证。

必须配置 `DETECTOR_MODEL_PATH=/models/best.pt`、`DETECTOR_MODEL_SHA256`、不可变的 `DETECTOR_MODEL_VERSION`、`DETECTOR_THRESHOLDS_PATH=/models/thresholds.json`、`DETECTOR_CONFIDENCE=0.01`，并通过 `DETECTOR_API_TOKEN_FILE` 挂载至少 32 字符的随机 token。平台与 detector 使用相同 token。等待 `/readyz` 成功后才接入任务，不能把 `/healthz` 当成模型已加载。错误模型或阈值保持未就绪，不回退 COCO 权重。

WVP 非 root 容器需要可写的抓拍目录和历史目录；将持久抓拍目录挂载到 `/opt/wvp/snap` 并由运行 UID 拥有，历史目录也必须持久化。媒体地址和 SIP 接入沿用已验证的平台部署。抓拍读取不依赖先有磁盘缓存；后端重启可复用同通道已有的 RTP 推流，不关闭其他观看客户端。

## 能力范围与实测

训练类别为 helmet、gloves、vest、boots、goggles、none、Person、no_helmet、no_goggle、no_gloves、no_boots。不存在 no_vest；未检出背心不能推断未穿背心。装备和人员显示为目标，四个未佩戴类显示待人工复核，none 保持未知。检测置信分数不是已校准的安全概率。

2026-10-12 验证结果：平台后端 104 项测试零失败、零错误，1 项原有 SSRC 测试跳过；detector 30 项测试通过，包含真实 Uvicorn TCP 请求；前端 10 项状态回归检查及生产构建通过。真实摄像头链路验证了原帧/结果绑定和历史图片一致；已有 RTSP 观看流保持存活时重启 WVP，任务自动恢复；暂停 detector 后旧实时结果被清除，恢复服务后任务自动继续。YOLO 模式不使用外部视觉 token，联合模式也以实际摄像头原帧验证。

正式候选 YOLO26s 完成 100 轮训练。官方 train/val/test 为 1132/143/141 张，阈值仅由 val 冻结。独立 test mAP50=55.10%、mAP50-95=26.76%；冻结阈值下安全帽 Precision=94.4%、Recall=88.5%，未戴帽 Precision=28.6%、Recall=20.0%。因此该候选用于辅助标记、人工复核和集成验证，未通过未佩戴 PPE 自动告警验收。正式现场告警必须使用按摄像头/日期/完整片段隔离的独立标注数据重新验收。

## 回归验证

```bash
mvn test
node --test web/tests/aiAnalysis-history-state.cjs
python3 -m unittest discover -s ppe/scripts -p test_logic.py
```

Detector 测试在其隔离环境内安装 `requirements-api.txt` 和 `requirements-test.txt`，从 `detector/` 执行 `python -m unittest discover -s tests -v`。完整 GPU 环境仍须按 Dockerfile 的版本断言及实际 warmup 核对，不能以 mock 测试代替运行验证。
