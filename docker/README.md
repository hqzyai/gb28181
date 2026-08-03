可以在当前目录下：

1. 执行 `cp .env.example .env`；
2. 修改 `.env` 中所有 `CHANGE_ME_` 项，并确保凭据随机、唯一；
3. 使用 `docker compose config` 校验配置；
4. 使用 `docker compose up -d` 启动。

`.env` 已被 Git 忽略，禁止提交真实密码、SIP 口令和 ZLM secret。

使用`docker compose up -d --build --force-recreate`强制重新构建所有服务的镜像并删除旧容器重新运行

`.env`用来配置环境变量，在这里配好之后，其它的配置会自动联动。

`build.sh`用来以日期为tag构建镜像，推送到指定的容器注册表内（Windows下可以使用`Git Bash`运行）


其它的文件的作用暂不明确
