可以在当前目录下：

1. 执行 `cp .env.example .env`；
2. 修改 `.env` 中所有 `CHANGE_ME_` 项，并确保凭据随机、唯一；
3. 使用 `docker compose config` 校验配置；
4. 使用 `docker compose up -d` 启动。

`.env` 已被 Git 忽略，禁止提交真实密码、SIP 口令和 ZLM secret。

## AI 安全识别

`AI安全识别` 菜单会从浏览器中正在播放的 GB28181 视频抽取压缩帧，并发送给已登录鉴权的 WVP 后端。只有后端调用 OpenAI 兼容的视觉模型接口，API Key 不会返回浏览器。

启用前在 `docker/.env` 中配置：

```dotenv
AI_ANALYSIS_ENABLED=true
AI_ANALYSIS_API_URL=https://your-provider.example/v1/chat/completions
AI_ANALYSIS_API_KEY=replace-with-a-new-server-side-key
AI_ANALYSIS_MODEL=replace-with-a-vision-capable-model
AI_ANALYSIS_INTERVAL_SECONDS=10
AI_ANALYSIS_MAX_CONCURRENT=2
AI_ANALYSIS_MIN_REQUEST_INTERVAL_SECONDS=3
```

必须使用新签发且从未出现在源码、文档、日志或截图中的 Key。模型服务需要兼容 OpenAI 多模态 `chat/completions` 请求。

使用`docker compose up -d --build --force-recreate`强制重新构建所有服务的镜像并删除旧容器重新运行

`.env`用来配置环境变量，在这里配好之后，其它的配置会自动联动。

`build.sh`用来以日期为tag构建镜像，推送到指定的容器注册表内（Windows下可以使用`Git Bash`运行）


其它的文件的作用暂不明确
