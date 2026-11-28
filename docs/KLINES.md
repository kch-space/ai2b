# 闭合 K 线采集与接口

更新日期：2026-11-27。当前实现范围为 Binance USD-M 的 BTCUSDT、ETHUSDT 永续合约，周期固定为 `1m`；仅保存闭合 K 线，无交易所密钥要求。

## 启动与页面

按 [本地开发说明](DEVELOPMENT.md) 启动前后端。已有开发库无需重建，Flyway V2 新建 `market_kline`。先有合约元数据且状态为 `TRADING` 才能采集；启动时元数据同步早于 K 线任务，无元数据时会退避重试。

详情入口为 `http://127.0.0.1:5173/#/market/BTCUSDT` 与 `#/market/ETHUSDT`，首页及合约列表提供链接。页面支持最近 1 / 6 / 24 小时，使用 UTC+8 展示时间。蜡烛图和成交量仅绘制已存分钟，缺失处留空；鼠标及左右方向键可查看精确数值，移动端可横向滚动。原始价格和成交量按十进制字符串传输，绘图坐标才转为浮点数。

页面每约 15 秒查询数据库，任务执行中每约 3 秒查询；查询失败保留上次读取的数据并显示错误。切换合约或离开页面会取消旧请求。

## 采集、恢复与限制

- 启动 15 秒后开始，每 15 秒调度一次；dev YAML 的 `market.klines.enabled: false` 关闭自动任务，手动任务仍可提交。
- 调用 `/fapi/v1/time` 和 `/fapi/v1/klines`。使用本机与交易所时间较早者，并留两秒闭合缓冲；偏差超过五分钟暂停写入。连接超时五秒，单次请求超时十五秒。
- 首次目标为最近 1,440 个已闭合分钟。每页最多 499 根，每次任务最多四页；长时间停机后分多轮继续追赶，不无限排队。
- 每个合约只允许一个任务运行或排队；独立执行器单线程、队列容量二，当前仅支持一个后端实例负责采集。
- 时间必须按分钟对齐，闭合时间为开盘时间加 59,999 毫秒；校验 OHLC、成交量、精度和返回区间。未闭合记录丢弃，完全相同的重复记录去重，冲突重复或格式异常使本批不写入。
- 唯一键为 `(instrument_id, interval_code, open_time)`，已保存的分钟保留第一次入库值。每页新数据与检查点在同一事务提交；保存失败整页回滚，之前成功的页面保留。
- 检查点为 `source=BINANCE`、`stream_key=USD_M_FUTURES:{symbol}:1m`。`cursor_value` 存初始覆盖起点的 epoch 毫秒；`last_event_time` 为最后连续分钟的**开盘时间**，不是收盘时间。
- 稀疏页可保存合法的后续分钟，但检查点停在第一个缺口前。首分钟缺失时连续检查点为 null；下轮从缺口起点重新请求，补齐后继续推进。不会用零值生成缺失 K 线。
- 成功后冷却十秒；失败按 30、60、120 秒等指数退避，常规上限 900 秒，同时遵循更长的上游 `Retry-After`。429 / 418 支持秒数或 HTTP 日期，451 至少退避五分钟。网络故障和上游故障由两个合约共享暂停期限，避免轮流重复请求。
- 覆盖起点、连续检查点、最近写入时间持久化。运行状态、尝试时间、错误、失败次数与暂停期限只存内存，重启会重置；重启后的数据进度从数据库恢复。后续若扩展为多个实例，需要数据库租约与共享限流。
- 当前持续保留采集到的历史，无自动删除或归档。查询最多返回最近 24 小时；尚未提供任意历史区间、其他周期、WebSocket 或其他币种采集。

## 查询接口

`GET /api/v1/instruments/{symbol}/klines?hours=6`

`symbol` 只接受 `BTCUSDT`、`ETHUSDT`；`hours` 为 1–24 的整数，默认 6。接口只读数据库，不触发上游请求。返回：

| 字段 | 含义 |
| --- | --- |
| `instrument` / `interval` | 合约基础信息 / 固定 `1m` |
| `start` / `end` | 请求窗口首尾分钟的开盘时间，UTC |
| `items` | 按开盘时间升序的已存闭合 K 线 |
| `expectedBars` / `missingBars` | 期望分钟数 / 缺失分钟数 |
| `gaps` | 缺口的首尾开盘时间（含端点）及分钟数 |
| `quality` | `UNAVAILABLE` 空窗口、`GAPPED` 缺失、`COMPLETE` 完整 |
| `stale` | 没有数据或窗口最后一分钟尚未入库；与内部历史缺口分开判断 |
| `collector` | 状态、是否运行、错误、下次允许时间及持久化检查点 |
| `serverTime` / `requestId` | 本次后端查询时间 / 请求标识 |

K 线包含 `openTime/closeTime`、`open/high/low/close`、`volume`（基础资产数量）、`quoteVolume`（USDT 成交额）、`trades`、`takerBuyVolume/takerBuyQuoteVolume`、`receivedAt`。主动买量不等于净流入，不由此推断开多或开空。

`POST /api/v1/instruments/{symbol}/klines/sync`，请求头必须包含 `X-AI2B-Action: collect-klines`。正常返回 202 和 `collector/requestId`，代表后台任务已受理；后续查询确认完成或失败。状态包括 `IDLE/QUEUED/RUNNING/SUCCESS/CATCHING_UP/ERROR`。请求头不是认证机制。

参数错误返回 400，未支持的 symbol 返回 404，合约元数据未就绪返回 503；运行中或处于退避时手动提交返回 429 和 `Retry-After`。已受理任务的上游错误通过后续查询的 `collector.error` 展示。只有应用健康为 UP 不代表行情新鲜，应结合 `quality/stale/collector` 判断。

```bash
curl 'http://127.0.0.1:8080/api/v1/instruments/BTCUSDT/klines?hours=24'
curl -X POST -H 'X-AI2B-Action: collect-klines' \
  http://127.0.0.1:8080/api/v1/instruments/ETHUSDT/klines/sync
```

## 验证范围

2026-11-27 已在授权的 MySQL 8.0.27 开发库完成 V2 迁移，实际调用 Binance 后 BTC / ETH 最近 24 小时窗口各有 1,440 根闭合 K 线、零缺口，并观察到新分钟自动写入。重启后覆盖起点不变，连续检查点继续前移。未向真实库注入模拟数据或人为制造缺口。

K 线阶段后端 37 项、前端 37 项测试通过；新增市场指标后当前为后端 55 项、前端 53 项。覆盖 K 线解析、事务/幂等、缺口恢复、限流退避、时钟偏差、重启进度、API 校验，以及前端图表、精确原始值、区间切换、异步采集、自动轮询、异常保留数据与取消请求；类型检查和生产构建通过。桌面、移动端与交互检查记录见忽略的 `output/playwright/`。

此前真实网络出现过 HTTP 451；当前采集成功不保证未来上游持续可用。规则、事件与通知仍未实现，后续应仅以连续完整窗口计算规则，并隔离历史补录与实时触发。
