# AI2B · Telegram 行情分析助手

关注指定币种、监控币安 USDT 永续行情、推送异动与分析，通过 Telegram 结合新闻和最新行情追问。程序通过终端配置，通过电报使用。

仅使用 Python 3.10+ 标准库；只读取公开行情，不连接交易账户、不下单。

## 本地启动

```bash
git clone https://github.com/kch-space/ai2b.git
cd ai2b
python3 app.py
```

首次启动进入终端配置：Telegram Token、数字 Chat ID、用户白名单、模型服务、关注币种、分析周期、监测阈值和推送规则。Token 与 API Key 隐藏输入。配置保存到 `.data/`，之后交互启动可选择复用或重新配置。

单独配置、配置完成后退出：

```bash
python3 app.py --configure
```

后台运行复用配置、不询问：

```bash
python3 app.py --non-interactive
```

模型可暂不配置，此时 `/price`、`/analyze` 和自动规则报告仍可用；AI 对话需要模型配置。

## Docker 启动

项目只有一个 `compose.yaml`。首次在终端运行配置，保存到数据卷后启动后台服务：

```bash
docker compose build
docker compose run --rm ai2b --configure
docker compose up -d
docker compose logs -f --tail=100 ai2b
```

修改配置时先暂停后台服务，避免同一机器人运行多个实例：

```bash
docker compose stop ai2b
docker compose run --rm ai2b --configure
docker compose up -d
```

升级代码使用 `git pull --ff-only` 和 `docker compose up -d --build`。原 `ai2b_data` 卷继续保存设置、密钥、关注列表、对话、预警和报告。不开放入站端口，无需浏览器或反向代理。

## 在电报中使用

| 输入 | 功能 |
| --- | --- |
| `/watch BTC ETH` | 添加关注 |
| `/unwatch BTC`、`/list` | 取消关注、查看列表 |
| `/price BTC` | 最新成交价、标记价、24h 涨跌与数据时间 |
| `/analyze BTC` | 技术规则报告，不调用模型 |
| `/ask BTC 这次上涨有量能支持吗？` | 结合新闻和行情回答 |
| `分析一下 BTC` → `关键支撑在哪里？` | 自然语言分析与连续追问 |
| `/alert BTC above 100000` | 单次到价预警，向下用 `below` |
| `/monitor 15 2 30 240` | 窗口分钟、异动百分比、冷却分钟、定时分析分钟 |
| `/pause`、`/resume` | 暂停或恢复自动异动与定时分析 |
| `/status`、`/reset` | 查看运行状态、清空自己的对话 |

默认每15秒轮询，约15分钟涨跌达到2%时触发，同币种冷却30分钟，每四小时生成一次分析。参数可在启动配置或 `/monitor` 修改。每日 Telegram AI 请求默认最多50次（UTC日，失败计数，重启不清零）。

每次追问重新读取快照；新闻使用 CoinDesk/Cointelegraph 24小时内 RSS 标题摘要，附来源和时间，模型只能引用本次提供的新闻。无新闻、行情过期或模型不可用时明确说明。

## 配置、数据与限制

- 本地数据保存到 `.data/`，Docker 保存到 `/data` 的命名卷；两者独立。Token/API Key 保存在权限 `0600` 的 `secrets.json`，不显示已有值、不提交到 Git。
- 只接受配置 Chat ID 的消息；群聊要求用户白名单。对话按聊天与用户隔离，最近四轮上下文持久保存。
- 监测使用定时轮询，非逐笔行情。断线超过三个轮询间隔重新预热完整窗口，避免把数据缺口当成连续异动。
- 规则使用已收盘 K 线、EMA、RSI、ATR、量能及关键价位；合约数据包含标记价、指数价、资金费率与持仓量。资金测算只使用配置的参考权益和杠杆，不读取真实账户。
- 真实行情失败不会自动切换合成数据。体验演示使用 `python3 app.py --demo`，报告标注演示，自动推送暂停。
- 新闻并非全网搜索，不保证完整覆盖；技术规则未经收益验证。分析对应消息中的快照时间，不能视为确定性盈利预测。

[启动配置与电报命令](docs/telegram.md) · [Docker 运维与迁移](docs/docker.md) · [数据来源与风险测算](docs/data-sources.md)

## 开发与测试

```bash
python3 -m unittest discover -s tests -v
```

测试覆盖配置保存与隐藏密钥、权限隔离、新闻引用、连续对话、监测窗口、重启冷却、合约数据与风险测算。外部服务使用模拟接口，实际连接需要自己的凭据与网络。

```text
app.py                  启动、终端配置、停止处理
analyst/configure.py     配置向导与环境参数初始化
analyst/telegram_bot.py  长轮询、命令、对话与自动监测
analyst/service.py       行情与分析服务
analyst/market.py        币安公开合约数据与演示数据
analyst/analysis.py      确定性指标与风险测算
analyst/news.py          新闻标题摘要采集
analyst/integrations.py  模型、Telegram 投递与密钥
analyst/storage.py       SQLite 持久化
compose.yaml            后台服务与持久数据卷
```
