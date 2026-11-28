# M1 · 5m 价格预警与通知

[返回项目介绍](../README.md)

更新日期：2026-11-28。固定价格规则、MOCK 与可选 Telegram 已实现；真实 MySQL V4/V5 及长期通知验收待完成。

## 范围与启动

现有启动命令不变，已有库升级先备份，不重新建库。Flyway **V4** 新增：固定规则版本、实时 K 线标记、检测状态、事件/history、通知 outbox 与尝试审计表；不修改 V1–V3，不清理已有行情；V5 增加集成配置、Telegram 队列扩展与 AI 报告，见 [集成说明](INTEGRATIONS.md)。

打开 `http://127.0.0.1:5173/#/alerts`，或从导航进入“事件中心”。支持活动/恢复筛选、每页 20 条、事件证据、转换历史、MOCK/Telegram 通知及尝试审计；每 10 秒刷新。规则为只读工程示例，不提供配置写接口。

当前支持 BTCUSDT / ETHUSDT，默认检测开启：`market.alerts.enabled`；生产环境可用 `ALERTS_ENABLED=false` 停止检测与 worker，历史保留。仍仅监听本机，**未实现认证，不应直接开放公网**。

## 规则口径

- 5m 变化率：`latestClose / baselineClose - 1`，当前及五分钟前收盘价，需 **6 根连续闭合 1m K 线**。
- `PRICE_SURGE` / `PRICE_DROP`：方向变化 ≥ 0.05（5%）开 P1；≥ 0.10（10%）开/升 P0。
- 方向变化 ≤ 0.02（2%），连续 **2 个不同且相邻的有效窗口**才恢复。反向变化也满足该方向恢复条件。
- 不在触发区间但尚未达到恢复阈值时保留事件，避免边界抖动。P0 活动事件不自动降级。
- 同级重复命中只更新证据、时间、计数和 UPDATE 历史。距上次通知 ≥ 15 分钟且方向变化比上次通知增加 ≥ 1 个百分点，才 REMIND；ESCALATE 绕过冷却。
- 阈值以小数比例保存，API 精确价格/比率是十进制字符串。参数为工程示例，未回测，不代表收益或交易建议。

## 实时、历史与 UNKNOWN

只有自动调度连续追上的新 K 线才与行情同事务写 `market_kline_live`。首次启动/进程重启后的第一次成功采集、手动同步、超过一个缺失分钟的追赶都按历史处理；重复历史记录不能再被提升为实时。

检测还要求 6 个实时标记均在当前后端进程启动后写入。因此首次启动/重启、历史补齐后，需要至少 6 根新实时 K 线重新预热；不在重启时重播旧窗口。待发送 PENDING outbox 不依赖此预热，可从持久化队列恢复。MOCK 过期 PROCESSING 租约可回收；真实 Telegram 过期租约结果未知，进入 DEAD 待人工核对，不自动重发。

以下情况输出 `UNKNOWN`：样本不足、缺口、未闭合、无效样本、非交易合约、陈旧数据、历史或预热。UNKNOWN **不开事件、不恢复事件、不产生通知**。恢复计数按已处理的相邻有效分钟判断；每分钟采集前短暂缺最新一根的轮询不会取消上一分钟的有效恢复结果，但跳过一个有效窗口会重新开始恢复计数。

采集检查点单步前进且距上次写入 ≤ 90 秒才标实时；检测只看当前闭合目标窗口，不遍历历史追发预警。显式调用纯规则计算时，收盘超过 90 秒也返回 STALE。

## 持久化与一致性

- 不可变 `alert_rule_version` 保存 v1 参数，事件引用版本，并保存当时阈值与价格证据。**后续规则 CRUD/切换版本尚未实现**。
- `alert_rule_state` 保存已处理窗口，重复输入不会重复增加命中/恢复计数。
- 用标的行锁串行处理该标的检测；采集写入也使用同一标的锁。活动 fingerprint 唯一约束保证同标的/规则/方向最多一个活动事件。
- OPEN / UPDATE / ESCALATE / REMIND / RESOLVE 写 history；应通知的 OPEN / ESCALATE / REMIND / RESOLVE 与 outbox **同一事务提交**。失败回滚后可重试，不留下半个事件。
- RESOLVE 同事务清空 active fingerprint，之后新命中可创建另一个历史事件。

## 通知：MOCK 与可选 Telegram

MOCK worker 用行锁领取任务，提交租约后在事务外调用 mock 适配器。不会连接外部通知服务，不需要 token，不发送真实消息。

状态为 `PENDING → PROCESSING → SIMULATED`；失败指数退避（5/10/20/40/80 秒），最多 5 次后 DEAD。30 秒租约到期可回收，lease token 防止旧 worker 覆盖新任务结果。每次失败、过期和模拟成功另存审计，成功后不抹去此前错误。

`provider_message_id=mock:<outbox id>` 是稳定模拟编号，**SIMULATED 不能称为真实送达**。Telegram 按明确保存/启用配置接入，测试每次须确认；本阶段不提供 DEAD 人工重试接口。

V5 已增加独立 Telegram worker：启用后，后续符合等级的应通知事件转换会新增 Telegram 队列；429 可重试，网络/超时/5xx 结果未知或真实租约过期进入 DEAD 待核对，改配置取消旧队列。

| Telegram 配置状态 | 当前渠道摘要 | 后续通知行为 |
| --- | --- | --- |
| 未配置 / 已保存但关闭 | MOCK | 仅创建模拟记录 |
| 已保存并启用 | MOCK+TELEGRAM | 保留 MOCK，并为符合配置的新通知增加 Telegram 队列 |
| 再次关闭 / 修改配置 | 以当前启用状态为准 | 取消旧版本待处理 Telegram 任务，历史记录保留 |

**配置真实 Telegram 不会自动隐藏、删除或停止 MOCK。** 目前没有 MOCK 隐藏/关闭选项；后续策略见 [路线图](ROADMAP.md)。Telegram SENT 仅表示服务商接受，不等于接收人已阅读。手动测试消息独立确认，不需要自动通知开关开启。

事件详情可手动调用 AI 研究，报告通过 `/api/v1/integrations/ai/reports/{eventId}` 读取；AI 不参与规则或自动通知。密钥/启用/确认/可靠性边界见 [集成说明](INTEGRATIONS.md)。

## 只读 API

| 接口 | 内容 |
| --- | --- |
| `GET /api/v1/alerts/rules` | 固定规则版本、各币检测状态、enabled、simulation |
| `GET /api/v1/alerts/events?page=1&pageSize=20&status=ALL` | 分页事件与 channel/simulation 配置摘要；status 为 ALL / ACTIVE / RESOLVED；pageSize 1–100 |
| `GET /api/v1/alerts/events/{uuid}` | 事件、完整 history、MOCK/TELEGRAM 通知与尝试审计；不存在为 404 |

事件使用 snake_case 数据字段（如 `rule_code`、`version_number`），`evidence` 为解析后的结构化证据；时间是 UTC ISO 字符串，前端按 Asia/Shanghai 展示。查询不会调用交易所或通知服务。`channel` / `simulation` 是当前配置摘要，不能证明某条历史通知已发送；实际结果应查看每条通知的渠道、状态与尝试审计。

## 验证与未验证边界

自动化覆盖：精确阈值及方向、连续窗口与 UNKNOWN、实时/补录/重启隔离、重复输入、并发首次命中、冷却提醒、P0 升级、滞回与连续恢复、恢复后新事件、event/history/outbox 原子回滚、租约回收与 fencing、失败重试/DEAD、稳定 mock 编号、API 参数与证据、页面筛选/分页/错误态/取消请求。

最新上一轮验证快照为后端 83 项、前端 99 项测试以及生产打包、前端 typecheck/build 通过，见 [开发记录](DEVELOPMENT.md)。预警测试使用 H2 MySQL 兼容模式与模拟输入；集成测试另使用模拟 provider/隔离本机 HTTP，不访问真实通知渠道。真实 MySQL V4/V5 迁移、长时间行情触发、Telegram 送达、生产压力/延迟尚未验证，不能据此宣称完整 P0 或真实通知验收通过。此次仅更新文档，未重跑测试。
