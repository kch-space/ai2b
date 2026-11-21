# Docker 部署与维护

## 启动

项目只保留一份 `compose.yaml`，默认运行 Telegram 行情助手。安装 Git、Docker 和 Docker Compose 后：

```bash
git clone https://github.com/kch-space/ai2b.git
cd ai2b
cp .env.example .env
# 编辑 .env，填写 Telegram Token、数字 Chat ID 和模型参数
docker compose up -d --build
docker compose ps
docker compose logs -f --tail=100 ai2b
```

已有 `.env` 时直接修改原文件。模型参数为空时仍可生成规则报告，AI 对话需要完整模型配置。群聊必须设置允许操作的用户 ID；详细参数和命令见 [Telegram 说明](telegram.md)。

服务不发布网页端口，不需要设置服务器入站端口或反向代理。运行机器需要能够访问 `fapi.binance.com`、Telegram、新闻 RSS 和所选模型服务。同一个 Bot Token 只运行一个长轮询实例；使用过 webhook 的机器人需先移除 webhook。

Compose 使用非 root 用户、只读程序目录、日志轮转与 `restart: unless-stopped`。只有 `/data` 数据卷和 `/tmp` 临时目录可写。HTTP 健康检查已关闭，因为默认不启动 HTTP 服务。`docker compose ps` 显示运行不代表外部数据可达，请在电报用 `/price BTC`、`/ask BTC 行情怎么看？` 核对行情和模型连接。

## 更新与停止

```bash
git pull --ff-only
docker compose up -d --build
docker compose ps
```

旧版网页容器在原项目目录执行同一命令，会重建为 Telegram 模式并继续使用原 `ai2b_data` 卷，无需另一份 Compose 文件。之前用单独 Telegram Compose 文件启动的容器，也可直接用上述命令更新。

停止使用 `docker compose down`，再次启动使用 `docker compose up -d`。容器收到停止信号后会关闭服务，Compose 最多等待 15 秒。手动停止后需手动启动；电脑休眠或 Docker 引擎停止期间无法监控行情。

首次启动将 `.env` 中列出的参数作为默认值保存。已有保存设置优先于环境默认值；改模型连接时可停止服务后更新数据卷中的保存设置，监测阈值直接用电报 `/monitor` 修改。更新环境变量后执行 `docker compose up -d` 重建容器，`docker compose restart` 不更新环境变量。Token 和 API Key 由服务读取，不进入镜像。

## 数据保留与备份

设置、密钥、关注列表、对话、预警和报告保存在 `/data`，由 `ai2b_data` 命名卷持久化。实际卷名通常带项目名前缀，可通过 `docker volume ls` 查看。`docker compose down` 保留数据卷；`docker compose down -v` 会删除全部卷数据。

备份前暂停服务，完整复制数据目录：

```bash
docker compose stop ai2b
mkdir -p backups
docker compose cp ai2b:/data backups/data
docker compose start ai2b
```

示例适用于 `backups/data` 尚不存在的首次备份；再次备份换一个目录名。备份包含密钥，按私人数据保管。`backups/` 已被 Git 忽略。

## 从本地 Python 版迁移

Docker 不会自动读取项目下的 `.data/`。先停止本地 Python 服务与容器。以下示例要求目标数据卷为空；已有 Docker 数据时先备份并决定保留哪一份，SQLite 数据库不能用文件复制合并。

```bash
docker compose stop ai2b
docker compose run --rm --no-deps --user 0 --cap-add CHOWN --cap-add DAC_OVERRIDE \
  --entrypoint sh -v "$PWD/.data:/source:ro" ai2b \
  -c 'cp -R /source/. /data/ && chown -R 10001:10001 /data'
docker compose up -d
```

从备份恢复时，将 `$PWD/.data` 换为备份目录。临时迁移容器使用 root 修正归属；正常服务仍以 UID 10001 运行。

## 演示与连接检查

体验合成数据时先停止常规容器，再启动使用同一数据卷的临时进程：

```bash
docker compose stop ai2b
docker compose run --rm ai2b --telegram-only --demo
```

按 Ctrl+C 退出，执行 `docker compose up -d` 恢复真实行情。演示模式暂停自动行情推送，手动查询和对话带演示标识。

币安合约连接可在服务器检查：

```bash
curl -f 'https://fapi.binance.com/fapi/v1/premiumIndex?symbol=BTCUSDT'
```

应返回标记价、指数价、资金费率和时间等字段。HTTP 451 等地区或网络错误会明确显示，不会用模拟或现货行情代替。

容器内 `localhost` 指向容器自身。连接宿主机模型时，Docker Desktop 可使用 `host.docker.internal`；当前模型接口对非回环地址要求 HTTPS，因此该地址也需要 HTTPS 模型端点。

如果偶尔需要本地网页，直接运行 `python3 app.py`，默认端口 8765。Docker 的默认入口继续使用电报。
