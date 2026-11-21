# AI2B Telegram 行情与对话

使用项目已有的币安 USDT 永续合约行情、已收盘 K 线、资金费率与持仓量、确定性技术规则和新闻采集。运行时仍仅依赖 Python 标准库，无交易所密钥或下单接口。

## 配置与启动

1. 用 Telegram 官方 BotFather 创建机器人，取得 Token，并先向自己的机器人发送 `/start`。
2. 将 `.env.example` 复制为 `.env`。填写 `AI2B_TELEGRAM_BOT_TOKEN` 和数字 `AI2B_TELEGRAM_CHAT_ID`。Chat ID 可在自己 Bot API 的 `getUpdates` 响应中查看 `message.chat.id`。群聊 ID 为负数，需配置 `AI2B_TELEGRAM_ALLOWED_USER_IDS=允许操作的用户数字ID`。
3. AI 对话需要 `AI2B_LLM_BASE_URL`、`AI2B_LLM_MODEL`、`AI2B_LLM_API_KEY`，使用 OpenAI 兼容聊天接口。模型地址应包含 API 基础路径，例如 `https://api.openai.com/v1`。本机回环地址支持 HTTP，其余服务要求 HTTPS。
4. 启动 `python3 app.py --telegram-only`。该模式不创建 HTTP 服务器，启用行情监控、Telegram 对话与推送。按 Ctrl+C 停止。进程持续运行才会监控行情。

纯 Telegram 的 Docker 启动：

```bash
docker compose -f compose.telegram.yaml up -d --build
docker compose -f compose.telegram.yaml logs -f --tail=100 ai2b
```

该配置不发布端口，关闭原镜像的 HTTP 健康检查；进程重启、日志轮转、非 root 运行、持久数据卷仍保留。容器运行状态不代表行情、模型或 Telegram 网络正常，请用 `/price`、`/ask` 和日志核对。

已经在运行网页版时，先 `docker compose down`，再切换同一项目下的 Telegram 配置。两种配置使用同一 `ai2b_data` 卷，切换无需删除卷。同一个 Bot Token 不可同时运行多个长轮询实例；已配置 webhook 时需移除 webhook 再启动此模式。更新后使用相同的 `-f compose.telegram.yaml` 执行 `up -d --build`；停止使用 `down`，不要附加 `-v`。

原网页模式也可以接收电报对话：启动前设置 `AI2B_TELEGRAM_CHAT_ENABLED=true`、`AI2B_MONITOR_ENABLED=true`、`AI2B_TELEGRAM_ENABLED=true`。已有保存设置优先于环境默认值；可通过原设置 API 修改新增字段，或改用 `--telegram-only` 强制启用这三个开关。初次启动的环境参数会保存，之后修改 `.env` 不会覆盖已有设置；纯 Telegram 的监测阈值可通过 `/monitor` 修改。

## 电报命令

| 输入 | 功能 |
| --- | --- |
| `/watch BTC ETH` | 关注币安 USDT 永续币种 |
| `/unwatch BTC` | 取消关注，保留已有历史报告与价位预警 |
| `/list` | 查看关注列表 |
| `/price BTC` | 查询最新成交价、标记价、24h 涨跌与数据时间，不调用模型 |
| `/analyze BTC` | 生成技术规则报告，不调用模型 |
| `/ask BTC 这次上涨有量能支持吗？` | 结合新行情与新闻回答 |
| `分析一下 BTC` | 自然语言请求 AI 分析 |
| `关键支撑在哪里？` | 继续上次币种的对话，重新取快照 |
| `/alert BTC above 100000` | 创建单次向上到价预警；向下用 `below` |
| `/monitor 15 2 30 240` | 设置窗口分钟、异动百分比、冷却分钟、定时分析分钟（0关闭定时分析） |
| `/pause`、`/resume` | 暂停或恢复异动与定时分析；手动查询、已有单次价位预警和原每日简报不受影响 |
| `/status` | 查看监测参数、市场状态与 Telegram AI 日调用次数 |
| `/reset` | 清空自己的对话上下文 |

对话支持英文币种代号、完整 USDT 交易对及比特币、以太坊、索拉纳、狗狗币、瑞波币、币安币等中文名。首次提问要指定币种，后续可直接追问，一次最多三个币种、2000字。新闻链接由程序核对模型引用后附加，不接受模型自行生成的链接。对话按聊天和用户隔离，最近四轮对话保存在原 SQLite 数据库中，重启保留。群聊仅允许白名单用户发指令，不处理转发消息。

## 自动监控

- 原有行情轮询默认15秒，使用公共接口定时获取，非逐笔 WebSocket 行情。积累完整监测窗口后，约15分钟涨跌绝对值达到2%触发价格事件，再补规则报告或 AI 综合分析；同币种默认冷却30分钟。
- 默认每币种每四小时一次定时报告，首次报告从第一次监测开始计时。设置间隔0关闭。监控状态、关注列表和推送冷却保存到原数据库，重启不重置。超过三个轮询间隔没有新数据时重新预热窗口，不将断线变化误标为连续监测；过期的队列事件会丢弃。
- 单次价格预警继续使用原有逻辑，在到达指定价位时推送一次。使用 `/unwatch` 不会删除已设置的独立预警。
- 新闻来自现有 CoinDesk/Cointelegraph RSS，保留24小时内有发布时间的相关标题摘要与宏观背景，默认缓存五分钟，不是全网搜索，也不能断言新闻导致了价格变化。缺失或部分不可用会明确标注。
- 价格事件先推送，AI 失败后说明未完成；没有模型配置时自动分析使用规则报告。AI 请求最多每天50次（UTC日），在请求前持久计数，失败也计数，重启不归零。这是调用次数上限，不是美元费用上限。可配置 `AI2B_TELEGRAM_DAILY_AI_CALLS`。原网页 AI 请求沿用原有设置，不受 Telegram 调用次数限制。
- `--demo` 仍使用合成数据并标注；自动异动和定时推送暂停，手动查询或 AI 对话可能发送明确标注的演示内容。

配置项参见 `.env.example`：`AI2B_MONITOR_WINDOW_MINUTES`、`AI2B_MONITOR_CHANGE_PCT`、`AI2B_MONITOR_COOLDOWN_MINUTES`、`AI2B_MONITOR_REPORT_MINUTES`。服务只读取已收盘 K 线，保持原有合约价格、资金费率与风险测算语义。所有分析对应消息标注的快照时间，非经回测的交易收益保证。
