# Docker 部署与维护

## 启动与配置

在克隆的 `ai2b` 项目目录中执行：

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f --tail=100 ai2b
```

浏览器访问 `http://127.0.0.1:8765`。首次健康检查约在启动后 30 秒执行；`healthy` 表示本地 HTTP 服务可响应，行情和 Telegram 网络状态仍需在网页中查看。

模型与 Telegram 可以通过「偏好与连接」配置。需要通过环境变量初始化时，在首次启动前执行 `cp .env.example .env` 并编辑 `.env`。Compose 会将列出的 `AI2B_` 连接参数注入运行时，不将 `.env` 复制进镜像。网页中保存的连接参数和密钥优先，修改 `.env` 后执行 `docker compose up -d` 重建容器使环境变量生效；`docker compose restart` 不更新环境变量。

宿主机端口可以在 `.env` 中设置 `AI2B_PORT=8766`，或在本次启动时指定：

```bash
AI2B_PORT=8766 docker compose up -d --build
```

此时访问 `http://127.0.0.1:8766`，容器内部端口仍为 8765。若本地 Python 版正在运行，可以用此方式并行体验 Docker 版。

容器使用 UID/GID `10001:10001`，程序目录只读，只有 `/data` 数据卷和 `/tmp` 临时目录可写。命名卷首次挂载会继承镜像中 `/data` 的权限。健康检查不依赖额外的 curl 工具。

## 在远程服务器访问

Compose 只发布服务器的回环地址。先在服务器项目目录运行 `docker compose up -d --build`，然后在自己的电脑执行：

```bash
ssh -N -L 8765:127.0.0.1:8765 用户名@服务器地址
```

保持 SSH 连接，浏览器访问 `http://127.0.0.1:8765`。自己的 8765 已被占用时，使用 `-L 8766:127.0.0.1:8765`，浏览器改为访问 8766。

当前没有用户登录和权限控制，Host 校验也仅接受 `localhost` / `127.0.0.1`。不要直接把端口发布到公网；需要域名和多人访问时，应先增加认证和受控的代理配置。

## 数据保留与备份

设置、密钥、持仓、自选、预警和报告保存在 `/data`，由 Compose 的 `ai2b_data` 命名卷持久化。实际卷名通常带项目名前缀，可以通过 `docker volume ls` 查看。

`docker compose down` 删除容器并保留数据卷。`docker compose down -v` 会删除数据卷。不要让多个运行中的服务共享同一个数据卷。

备份时先暂停服务，完整复制 SQLite 主文件、WAL 文件与密钥：

```bash
docker compose stop ai2b
mkdir -p backups
docker compose cp ai2b:/data backups/data
docker compose start ai2b
```

示例适用于 `backups/data` 尚不存在的首次备份；再次备份请改为新的目录名。`backups/` 已被 Git 忽略。备份含明文密钥，需要按私人数据保管。

## 从本地 Python 版迁移

Docker 版不会自动读取项目下的 `.data/`。如需迁移，先停止本地 Python 服务与 Docker 服务，避免复制过程中数据变化。以下命令应在项目目录运行，且目标数据卷应为空；如果 Docker 版已有数据，先备份并决定保留哪一份，SQLite 数据库不能用文件复制合并。

```bash
docker compose stop ai2b
docker compose run --rm --no-deps --user 0 --cap-add CHOWN --cap-add DAC_OVERRIDE \
  --entrypoint sh -v "$PWD/.data:/source:ro" ai2b \
  -c 'cp -R /source/. /data/ && chown -R 10001:10001 /data'
docker compose up -d
```

从备份恢复时，将上例的 `$PWD/.data` 换为 `$PWD/backups/data`。临时迁移容器使用 root 修正文件归属；正常服务仍以 UID 10001 运行。

## 演示模式

先停止常规容器，再启动使用同一配置和数据卷的临时演示容器：

```bash
docker compose stop ai2b
docker compose run --rm --service-ports ai2b --demo
```

按 `Ctrl+C` 退出，然后执行 `docker compose up -d` 恢复真实行情。演示报告有明确标识；价格预警与每日自动简报暂停。已启用的分析后自动推送仍可能发送带演示标识的报告，体验前可在网页关闭推送。

## 更新与停止

```bash
git pull --ff-only
docker compose up -d --build
docker compose ps
```

容器收到停止信号后会关闭服务，Compose 最多等待 15 秒。`restart: unless-stopped` 用于异常退出和 Docker 引擎重启后的恢复；手动停止后需手动启动。电脑休眠或 Docker 引擎停止期间无法持续盯盘。

容器内的 `localhost` 指向容器自身。连接宿主机的模型时，Docker Desktop 可以通过 `host.docker.internal` 访问；当前模型接口对非回环地址要求 HTTPS，因此该地址需要 HTTPS 模型端点。公共 HTTPS 模型服务可直接使用。后续如需连接同一 Docker 网络的 HTTP 模型，应单独增加明确的受信模型地址配置。
