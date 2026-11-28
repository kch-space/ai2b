# BTC / ETH 市场指标

更新日期：2026-11-28。指标与 K 线在同一合约详情页展示，范围固定为 BTCUSDT、ETHUSDT 的 Binance USD-M USDT 永续合约。接口仅返回市场数据，不返回用户余额、持仓、API Key 或 Secret。

## 指标口径与数据源

| 类型 | 来源 | 保存值与口径 |
| --- | --- | --- |
| `OPEN_INTEREST` | `/futures/data/openInterestHist`，`period=5m` | `quantity` 为基础资产数量、`notional` 为 USDT 名义价值；两者不混用 |
| `FUNDING_SNAPSHOT` | `/fapi/v1/premiumIndex` | `markPrice`、`indexPrice`、`rate`（来源 `lastFundingRate`）、`nextFundingTime` |
| `FUNDING_HISTORY` | `/fapi/v1/fundingRate` | 已结算的 `rate` 与 `markPrice`，按实际 `fundingTime` 保存；不预设结算周期 |
| `GLOBAL_ACCOUNT_RATIO` | `/futures/data/globalLongShortAccountRatio`，`period=5m` | 全市场净多 / 净空账户占比及多空比 |
| `TOP_ACCOUNT_RATIO` | `/futures/data/topLongShortAccountRatio`，`period=5m` | 保证金余额前 20% 用户的账户多空占比及多空比 |
| `TOP_POSITION_RATIO` | `/futures/data/topLongShortPositionRatio`，`period=5m` | 同一大户群体的多空持仓比例，不能当作账户数比例 |

多空比统一返回 `longShare/shortShare/ratio`，`ratio = 多 / 空` 使用来源值，允许来源舍入差异。费率按小数保存，页面将十进制字符串精确乘 100 展示百分数，如 `-0.00001234` 显示 `-0.001234%`。价格、数量、金额和比例在后端先做 BigDecimal 精度与范围校验，再保存为 JSON 十进制字符串；SVG 坐标才转浮点数。数量与名义价值变化、账户与持仓比例都不直接推断市场方向。

只有两个 `TOP_*` 请求携带 `X-MBX-APIKEY`。本地密钥来自 `backend/src/main/resources/application-dev.yml` 的 `market.binance.api-key`，不会发送给浏览器；不使用 `market.binance.api-secret`，不签名、不请求个人账户或交易接口。缺少 Key 时这两个指标单独显示错误，其他指标仍采集。实际鉴权请求只允许发往 `https://fapi.binance.com`，不跟随重定向；配置其他来源时不会转发 Key。

官方接口说明：[Binance USD-M Market Data](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data)。接口可用性、权限和网络仍以实际响应为准。

## 采集与持久化

- 启动 20 秒后开始调度，每 30 秒检查到期任务；正常成功后下次采集对齐下一五分钟边界加 30 秒，留出发布缓冲，避免请求耗时逐轮累积漂移。稀疏 5m 页约一分钟后进入补采，实际执行还受调度与队列影响；dev YAML 的 `market.metrics.enabled: false` 只关闭自动任务。新时间点发布与实际任务执行之间可能短暂缺少最新样本，页面据实显示缺口。
- 独立执行器一个工作线程、队列容量二；每个 symbol 只允许一个排队或运行中的任务。当前只支持单后端实例采集，未实现多实例租约。
- OI 与三种比例每轮请求最近 288 个五分钟时间点，每次单页上限 500。重复覆盖最近 24h，修复该窗口内暂时缺失的记录；更早记录保留，不承诺修复超过 24h 的停机缺口，也不合成空数据。
- 资金费率历史每轮请求最近 24h；资金费率快照只从启动后积累，交易所没有为该接口提供本实现可回填的过去快照。页面将快照与已结算记录分开展示。
- V3 新建 `market_metric` 与 `market_metric_sync`。前者唯一键为 `(instrument_id, metric_type, event_time)`，相同时间保留首次写入值；每类每批的点与成功状态同事务提交。单项失败不会回滚其他指标已成功的批次。
- 后者持久化最近尝试 / 成功时间、下次允许时间、失败次数与安全错误。重启复用已有数据与退避期限，无需重建数据库。429 / 418、网络故障及上游故障的暂停期限在本指标模块中跨 BTC / ETH 共享；此模块与 K 线模块的限流状态独立。
- 常规失败按 60、120、240 秒等指数退避，上限 1,800 秒；遵循更长 `Retry-After`（秒数或 HTTP 日期），451 至少五分钟，鉴权拒绝至少十五分钟。空数据、格式错误、缺 Key 与存储错误分别处理，不覆盖旧数据，不向前端回传上游原始响应。
- 所有时间存 UTC，页面展示 UTC+8。5m 图表按事件时间绘制，缺失处断线；窗口的 288 点完整度与数据新鲜度分别判断。非历史结算类数据超过十五分钟未更新显示滞后，历史结算类以最近成功抓取时间判断新鲜度，不把低频结算间隔当作故障。
- 本阶段无自动删除、归档或全市场采集。数据库 JSON 为已校验的行情值，不包含密钥；未来需要 SQL 聚合时可新增类型化业务表。

## API

`GET /api/v1/instruments/{symbol}/metrics`：只读数据库，返回最近 24h 的六个独立 `streams`，不在查询过程中访问交易所。字段包括：

| 字段 | 含义 |
| --- | --- |
| `symbol/baseAsset/quoteAsset` | 标的与数量 / 金额单位 |
| `running` | 本 symbol 的任务正在排队或运行 |
| `sourceGate` | 指标模块共享暂停期限与安全错误 |
| `streams[].type/authentication/period` | 指标类型、是否需要 API Key、`5m` 或 `OBSERVATION` |
| `start/end/items/latest` | 查询首尾事件时间、升序数据点、最后一条；数据点包含 `eventTime/receivedAt/values` |
| `expectedPoints/missingPoints` | 5m 指标为 288 与缺失点数，观察类为 null，不假造结算频率 |
| `stale/quality` | `UNAVAILABLE/STALE/GAPPED/READY`，单项采集错误另见 `sync` |
| `sync` | 单项持久化尝试、成功、退避与错误状态 |
| `serverTime/requestId` | 查询时间与请求标识 |

`POST /api/v1/instruments/{symbol}/metrics/sync`：请求头 `X-AI2B-Action: collect-metrics`，异步受理后返回 202 和 `accepted/requestId`。接口以数据库状态限制重复采集；运行中或所有指标均在冷却时返回 429 和 `Retry-After`。symbol 不支持为 404，缺合约元数据为 503，缺 action 头为 400。此请求头不等于用户认证。

```bash
curl 'http://127.0.0.1:8080/api/v1/instruments/BTCUSDT/metrics'
curl -X POST -H 'X-AI2B-Action: collect-metrics' \
  http://127.0.0.1:8080/api/v1/instruments/ETHUSDT/metrics/sync
```

页面约每 30 秒读取数据库，任务执行中约每 3 秒刷新。刷新失败时保留上次读取结果并显示错误；离开详情页或切换标的取消旧请求。每种多空比都独立显示来源口径、数据时间、采样点数与错误。

## 测试

2026-11-27 市场指标阶段的历史快照：后端 55 项测试通过，新增覆盖数据精度、费率正负与百分比口径、时间窗口、重复 / 冲突、账户占比校验、官方地址限定、鉴权头隔离、原始错误脱敏、独立失败、共享退避、重启状态、幂等与事务。前端 53 项测试通过，覆盖图表缺口、精确数值、空数据、鉴权缺失、陈旧状态、轮询、手动采集、刷新失败保留与卸载取消；类型检查和生产构建通过。自动测试使用本地 fixture / H2，不向真实库写模拟行情。最新上一轮项目快照为后端 83 项、前端 99 项，见 [开发记录](DEVELOPMENT.md)；此次只更新文档，未重跑测试。

2026-11-27 已在授权 MySQL 8.0.27 开发库应用 V3，六类指标均通过真实 Binance 请求并入库；BTC / ETH 的 OI 与三种多空比各 288 点、缺失 0 点，结算历史各三条，费率快照正常。重启后原有记录逐条保持不变，采集状态与冷却时间恢复，新五分钟记录和快照继续写入，无重复。已检查桌面图表、结算记录展开、BTC / ETH 切换；390px 手机页面宽度与视口一致，图表可独立横向滚动，页面无指标错误。浏览器记录与截图位于忽略的 `output/playwright/`。这段是历史 V3/行情验证，不代表真实 MySQL V4/V5、Telegram/AI 或持续运行已验收。

## 后续开发

更多币种、指标规则（OI/费率等）、规则编辑、自选与规模验证仍待开发；已有指标展示不等于已接入对应预警规则。优先级与接入条件见 [路线图](ROADMAP.md)。
