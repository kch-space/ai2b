# Docker 部署与维护

## 首次启动配置

安装 Git、Docker 和 Docker Compose 后，在终端配置一次，再启动后台服务：

```bash
git clone https://github.com/kch-space/ai2b.git
cd ai2b
docker compose build
docker compose run --rm ai2b --configure
docker compose up -d
docker compose logs -f --tail=100 ai2b
```

交互命令需要实际终端；不要加 `-T`，不要通过日志面板填写密钥。配置向导隐藏 Token/API Key 输入，保存参数到数据卷后退出。后台服务使用 `--non-interactive` 读取同一卷的配置。模型可以暂不配置，自动分析使用规则报告；AI 对话需要完整模型连接。

配置过程不调用外部服务。启动后在电报用 `/price BTC`、`/ask BTC 行情怎么看？` 核对行情和模型连接。参数和命令见 [Telegram 说明](telegram.md)。

项目只有一个 `compose.yaml`。容器使用非 root 用户、只读程序目录、日志轮转与自动重启；只有 `/data` 卷和 `/tmp` 可写。没有 HTTP 服务器、端口映射或 HTTP 健康检查，无需设置入站端口或反向代理。运行机器需要连通币安、Telegram、新闻 RSS 和模型服务。

## 修改配置与更新

修改 Token、Chat ID、模型、白名单或监测规则时，先停止后台实例：

```bash
docker compose stop ai2b
docker compose run --rm ai2b --configure
docker compose up -d
```

升级代码：

```bash
git pull --ff-only
docker compose up -d --build
```

原数据卷继续使用；已有有效 Telegram 配置可直接启动。需要重新核对时按上方修改配置命令运行向导。同一个 Bot Token 只能运行一个长轮询实例，不要一边运行后台服务一边另起分析进程。配置命令只保存设置，不启动轮询。

停止使用 `docker compose down`，再次启动用 `docker compose up -d`。手动停止后需手动启动；机器休眠或 Docker 停止期间无法监控行情。

`.env` 可以作为无人值守的首次初始化方式，复制 `.env.example` 并填写参数即可。已有保存设置和密钥优先；更改连接推荐使用配置向导。环境变更用 `docker compose up -d` 重建容器，`restart` 不更新环境变量。缺少有效配置时后台进程退出，日志会提示交互配置命令。

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
docker compose run --rm ai2b --non-interactive --demo
```

按 Ctrl+C 退出，执行 `docker compose up -d` 恢复真实行情。演示模式暂停自动行情推送，手动查询和对话带演示标识。

币安合约连接可在服务器检查：

```bash
curl -f 'https://fapi.binance.com/fapi/v1/premiumIndex?symbol=BTCUSDT'
```

应返回标记价、指数价、资金费率和时间等字段。HTTP 451 等地区或网络错误会明确显示，不会用模拟或现货行情代替。

容器内 `localhost` 指向容器自身。连接宿主机模型时，Docker Desktop 可使用 `host.docker.internal`；当前模型接口对非回环地址要求 HTTPS，因此该地址也需要 HTTPS 模型端点。

本地直接运行 `python3 app.py` 使用终端配置并启动电报助手，不运行网络服务器。
