# Telegram 与 AI 服务端集成

[返回项目介绍](../README.md)

更新日期：2026-11-28。下述配置、调用与持久化代码已实现；真实 Telegram/AI 联调及 MySQL V4/V5 验收尚未执行。

## 已实现与边界

- Telegram：保存/替换/删除 Token、Chat ID、最低等级、证据选项；getMe 连接验证、从 `/start` 一次性发现 Chat ID、真实测试消息；启用后新预警写独立 Telegram outbox。
- AI：OpenAI-compatible Base URL、API Key、模型、temperature/max_tokens；模型列表连接检查、固定提示词回复测试；事件页手动研究与报告持久化。
- 所有集成默认关闭；**测试仍可在自动发送/事件分析开关关闭时执行**，但每次需独立确认。没有配置密钥时不可测试。
- AI 不自动扫描、不参与价格规则，不保证报告正确，也不包含自动下单、Bot 命令轮询或收益承诺。
- 前端只保留显示偏好；集成不再使用浏览器草稿，密钥由后端保存，不回显。

## 使用顺序

1. 已有开发库先备份，正常启动后端与前端，由应用 Flyway 加载公共与 vendor 迁移并应用 V5（不重建数据库、不改旧迁移、不清理行情）。初始化工具当前只运行公共 V1–V4，不能替代应用启动后的 V5 升级，见 [开发说明](DEVELOPMENT.md)。
2. `/#/settings/telegram` 先填 Bot Token，Chat ID 可留空，保持预警关闭，点击“保存 Token，继续配置”并确认。已有 Chat ID 也可手动填写。
3. 点击“连接机器人”，确认 getMe 外部请求；成功后打开显示的 `@机器人` 链接，在 Telegram 中发送 `/start`（群组发送 `/start@机器人用户名`）。随后点击“从 /start 获取 Chat ID”，确认联网、核对会话并点击“使用此 Chat ID”，再确认保存服务端配置。获取不会自动绑定、发送消息或启用预警。
4. 点击“发送测试消息”，确认接收 Chat ID；Telegram 返回 message_id 才显示发送确认，**不等于接收人已阅读**。
5. 确认目标无误后勾选“启用真实 Telegram 预警”并确认保存。只对后续应通知的 OPEN / ESCALATE / REMIND / RESOLVE 新增队列，不回播旧事件。
6. `/#/settings/ai` 配置服务商地址、模型与 Key，保存后确认执行连接/回复测试。模型列表没有列出该模型不等于回复必定失败，部分兼容服务不支持 `/models`。
7. 需要分析时允许手动 AI 事件分析；在事件详情点击“分析本事件”，确认将该事件价格/变化率/时间证据发送给指定模型。报告按事件与配置版本保存，仅文本展示。

未保存修改时禁用真实测试；请求带保存版本，避免前端测试过期配置。按集成串行执行并设 10 秒本机冷却，不自动轮询外部测试。网络取消/超时可能发生在服务商已接受之后，先核对记录再重试。

## 从 /start 获取 Chat ID 的边界

- 仅在本机用户点击并确认后调用 `getMe`、`getUpdates(timeout=0, limit=100)`；没有后台轮询、命令回复或自动 `/start` 绑定。
- 只从最早 100 条待处理更新中的普通消息筛选 `/start`、`/start@当前机器人`（可带参数），按 Chat ID 去重，列出私人会话/群组的名称、ID 和时间。完整消息及参数不返回、不写入审计或数据库；选择不是身份认证，必须人工核对接收人。
- 不传 `offset`（不确认消费或跳过更新）、不改变 `allowed_updates`、不删除 webhook。Telegram 仅保留有限时间的待处理更新，已被其他程序消费、积压超过 100 条或历史订阅未包含 message 时可能找不到；可以重新发送 `/start` 或手填 Chat ID。本功能不会清理积压来找最新消息。
- 不应与其他 `getUpdates` 程序同时使用；HTTP/JSON 409 显示 webhook/轮询冲突，用户自行处理或改为手填，不自动抢占。短请求未必能检测到其他消费者存在。
- 配置版本冲突、没有 Token、未确认或非本机操作都不能获取；10 秒冷却与操作审计仍生效。获取后改 Token/配置、刷新失败或版本变化会清空候选，避免误用旧接收人。
- 选择会话仅填入表单，通知开关不变；必须再次保存后才改变目标。尚无 Chat ID 时不能启用预警或发送测试消息。

## Telegram 启用与 MOCK 显示

| 操作 / 状态 | 页面渠道摘要 | 实际含义 |
| --- | --- | --- |
| 仅保存真实 Token / Chat ID，自动开关关闭 | MOCK | 凭据已保存，但新预警仍仅生成模拟记录 |
| 明确确认并启用真实预警 | MOCK+TELEGRAM | MOCK 保留；符合最低等级的新通知同时进入 Telegram 队列 |
| 停用 / 换目标 / 更新配置 | 按当前开关显示 | 旧版本待处理任务取消，已开始请求不能保证撤回，历史不删除 |

**启用 Telegram 不会让 MOCK 自动消失。** 当前没有隐藏/关闭模拟记录的开关；这属于 [后续路线图](ROADMAP.md) 中的可选开发。页面配置摘要不等于历史发送结果，应逐条查看 `channel`、`status` 与尝试审计。MOCK 的 SIMULATED 永远不代表真实送达。

连接验证与测试消息独立于自动开关，每次均需确认；测试成功不会自动启用预警，也不会回播旧事件。

## 密钥与部署

默认 dev 第一次保存密钥时生成随机 32 字节加密主密钥：

`~/.ai2b/integration.key`（文件权限 0600）

密钥值以 AES-256-GCM 加密后保存在 `integration_config.secret_ciphertext`；随机 IV 和按集成绑定的 AAD，接口仅返回 `secretConfigured`。不记录明文密钥、请求 URL 中的 Telegram Token 或服务商错误响应体。模型回复若回显当前 API Key 会替换为 `[REDACTED]`。

**数据库与主密钥必须分别备份。** 丢失/更换主密钥会导致已有密钥无法解密；读取失败不会生成新主密钥覆盖旧数据。不把主密钥放前端、Git 或日志，仓库忽略 `*.key`。

生产 profile 必须设置 `AI2B_MASTER_KEY`（32 字节随机值的 Base64，例如通过 `openssl rand -base64 32` 在你自己的终端生成；不要粘贴到聊天）。开发也可设置此环境变量替代文件。可用 `AI2B_KEY_FILE` 改本机文件位置；生产打包排除 dev 配置。

集成管理 API 限制回环来源、localhost Host、可信本机 Origin，并要求写操作 JSON 与 `X-AI2B-Action: integration-admin`。禁止跨站请求、不开放 CORS；**这不是完整用户认证**，不要改为公网监听或通过反向代理暴露；同一 OS 用户的恶意本地程序不在防护范围。

## AI 外部地址与 SSRF 防护

- 默认 `AI2B_AI_ALLOWED_HOSTS=api.openai.com`，增加其他服务商需后端环境变量逗号分隔的精确 hostname 白名单；前端无法修改白名单。
- 远程仅 HTTPS 与默认/443 端口，不允许 URL 用户名、密码、查询参数或片段。
- 默认禁用本地模型，显式 `AI2B_ALLOW_LOCAL_AI=true` 后才允许 localhost / 127.0.0.1 / ::1 的 HTTP/HTTPS 本地端口。开启即授权后端访问本机模型服务，勿在不可信共享环境使用。
- 外部 DNS 在实际建立连接时检查并由 HTTP 客户端使用同一解析结果；不允许私网、链路本地、回环、组播等地址。显式本地模型必须解析为回环地址。
- Telegram 固定 `https://api.telegram.org`，不允许自定义转发地址。
- 禁用重定向、系统代理和连接自动重试；连接超时 5s、总调用 20s、响应最大 128KiB；AI 文本最长 16000 字符。

当前地址策略不支持任意公网端口、内网域名、系统 HTTP 代理或复杂多提供方配置，这是安全边界而非“连接失败就绕过”。

## 通知可靠性

- 原 MOCK outbox/worker 不变，仍仅生成 SIMULATED；新增 Telegram worker 不会领取 MOCK。
- 事件/history/两类 outbox 同事务；Telegram 使用发送时的配置版本及固定 Chat ID，不将旧任务改发新接收人。
- 任何 Telegram 配置保存都会取消 PENDING / PROCESSING 旧版本任务；已开始的外部请求不能保证撤回，其完成结果另有尝试审计。
- Telegram 状态 `PENDING → PROCESSING → SENT`；429 明确拒绝可按服务商等待时间/指数退避重试（最多 5 次）。
- **Telegram sendMessage 无幂等键。** 网络/超时/5xx 结果未知、真实发送租约过期时进入 DEAD，需人工核对，不自动重发以免重复消息。无 DEAD 人工重试接口；队列 ID 写入消息用于核对。
- 配置停用/版本改变为 CANCELLED；SENT 只表示 Telegram 明确接受。保存版本 fencing 与尝试审计保留，不宣称端到端 exactly-once。
- `market.alerts.enabled=false` 同时停止自动规则和两个 worker；手动确认的测试接口独立。

## API

| 接口 | 说明 |
| --- | --- |
| `GET /api/v1/integrations` | 脱敏 Telegram/AI 配置、允许主机、本地模型策略、最近审计 |
| `PUT /api/v1/integrations/{telegram|ai}` | `{version, enabled, settings, secret?, clearSecret}`；secret 空/省略保留，clearSecret 删除 |
| `POST /api/v1/integrations/telegram/test/connect` | `{confirmed:true, version}`；getMe |
| `POST /api/v1/integrations/telegram/test/chats` | `{confirmed:true, version}`；getMe + 单次 getUpdates；返回 botUsername、chats（chatId/type/title/username/lastMessageAt），不自动保存 |
| `POST /api/v1/integrations/telegram/test/send` | 明确确认真实测试发送 |
| `POST /api/v1/integrations/ai/test/connect` | 明确确认请求模型列表 |
| `POST /api/v1/integrations/ai/test/reply` | 明确确认固定测试提示词调用，可能收费 |
| `POST /api/v1/integrations/ai/test/analyze` | 加 `eventId`，需要允许手动分析；持久化当时证据与研究报告 |
| `GET /api/v1/integrations/ai/reports/{eventId}` | 最近 10 份持久化报告 |
| `GET /api/v1/integrations/audit` | 最近 30 条安全操作审计，不含密钥 |

保存版本冲突 409，确认缺失/参数无效 400，非本机 403，请求过频 429，外部失败 502，主密钥错误 503。审计中 REQUESTED 没有最终 SUCCESS/FAILED 表示进程或提交曾中断，不能据此断言外部操作未执行。

## 验证与仍未验证

2026-11-28 本轮验证：后端 95 项、前端 107 项测试通过；生产打包及前端 typecheck/build 通过。覆盖加密持久化/权限/主密钥丢失、密钥不回传、Origin/Host/远程请求拒绝、地址白名单与私网拒绝、实际本机 HTTP 重定向不跟随、provider 请求/响应契约、明确确认/冷却/版本、新事件 Telegram outbox、改配置取消、429 重试、真实租约过期不重发、AI 报告持久化、前端确认与文本安全展示。

使用 H2/MySQL 兼容模式、模拟 provider 及隔离本机 HTTP 服务。浏览器使用空 H2 测试库联调了配置读取和确认弹窗；未输入真实密钥、未发 Telegram 消息、未调用外部模型、未修改真实 MySQL。真实 MySQL V4/V5、服务商可达性/地区限制、通知送达和长时间运行仍需用户配置与明确确认后验证。本轮增加 Token-only 保存、/start 筛选/去重、冲突/确认/版本/冷却、候选选择与清空、响应校验的自动测试；没有执行真实 Telegram 调用或新增浏览器验收。

## 后续接入

优先完成真实环境验收、主密钥备份恢复、完整认证与通知人工核对/重试；再按需接入 Telegram Bot 查询、更多模型提供方、本机模型兼容验证及自动异步 AI。当前仅手动单次读取 `/start` 辅助配置，不包含 Bot 查询/命令回复/webhook/后台轮询、自动 AI 或任意内网模型地址；不能通过放宽 SSRF 策略来绕过连接错误。具体依赖与验收条件见 [路线图](ROADMAP.md)。
