# ai2b · Crypto Intelligence

面向个人研究的加密货币市场监控、预警与分析平台，让重要信息主动找到人。

首期数据源为 Binance USD-M USDT 永续合约；不包含自动下单、交易密钥或真实资金管理。

## 当前开发状态

更新日期：2026-11-28。**数据采集、M1 价格预警闭环、Telegram 与手动 AI 研究的代码已实现；完整 P0/MVP 尚未验收。**

| 模块 | 已实现 | 当前边界 |
| --- | --- | --- |
| 工程与数据 | Java 21 / Spring Boot、Vue 3 / TypeScript、MySQL、Flyway V1–V5、健康检查 | 单实例、本机访问；完整用户认证未实现 |
| 合约市场 | 元数据自动/手动同步、搜索、状态筛选、分页与失败提示 | 全量列表是合约元数据，不代表全量币种已采集行情 |
| 行情与指标 | BTC/ETH 闭合 1m K 线、24h 补录、缺口补齐、重启续采；OI、费率、三种多空比 | 行情仅 BTCUSDT / ETHUSDT；未闭合 ticker、其他周期/币种未接入 |
| 预警闭环 | 5m 急涨/急跌、P0 升级、去重、连续窗口恢复、事件/history/outbox | 固定 v1 工程示例；补录、缺口、陈旧与预热窗口不触发实时预警 |
| Telegram | 加密配置、getMe 验证、测试消息、按等级发送新预警、发送审计 | 默认关闭；真实服务商送达尚未联调，Bot 查询未实现 |
| AI | OpenAI-compatible 连接/回复测试、事件手动研究、报告保存 | 默认关闭；每次分析确认目标/证据/费用，不自动扫描、不阻塞规则 |
| Web 设置 | 紧凑概览、独立市场与事件页、显示偏好、服务端集成设置 | 密钥不回显、不存浏览器；只支持本机管理 |

**已验证（上一轮快照，本次文档更新未重跑）：** 后端 83 项、前端 99 项测试通过，生产打包与前端 typecheck/build 通过；集成使用 H2/模拟 provider 与隔离本机 HTTP，浏览器验证了配置读取及确认弹窗。

**尚待真实验证：** MySQL V4/V5 迁移、长期采集/预警、Telegram 送达、AI 服务商连通性与回复。此前 Binance/MySQL 行情验证见[历史开发记录](docs/DEVELOPMENT.md)，不能替代新集成验收；上游曾出现 HTTP 451，不能保证持续可达。

## 页面导航

| 页面 | 地址 | 内容 |
| --- | --- | --- |
| 概览 | `/#/` | BTC/ETH 两张卡片、状态摘要、最多 4 条近期活动预警，不铺开全量合约 |
| 合约市场 | `/#/markets` | 按需搜索/筛选，默认每页 10 条，可选 25/50，长表格内部滚动 |
| 行情详情 | `/#/market/BTCUSDT`、`/#/market/ETHUSDT` | 1/6/24 小时 K 线、成交量、市场指标与采集状态 |
| 事件中心 | `/#/alerts` | 规则状态、证据、事件历史、通知记录和手动 AI 研究 |
| 设置 | `/#/settings` | 显示偏好、Telegram、AI、系统与规则；真实操作二次确认 |

## Mac 本地启动

需要 JDK 21、Maven 3.8.9+、npm 10+、MySQL 8.0.16+（支持 TLS）。Node.js 支持 22.22.2+（22.x）或 24.15+；本机已验证 Node 26.5.0，见 `frontend/.node-version`。

本机开发配置位于 Git 忽略的 `backend/src/main/resources/application-dev.yml`。新环境复制同目录 `.example`，填写真实值并设置权限：

```bash
cp backend/src/main/resources/application-dev.yml.example backend/src/main/resources/application-dev.yml
chmod 600 backend/src/main/resources/application-dev.yml
```

已有开发库不重新初始化；启动时 Flyway 自动升级到 V5，建议先备份。新库按[开发说明](docs/DEVELOPMENT.md#数据库配置与首次初始化)初始化。**文档更新不会执行迁移或外部请求。**

```bash
# 终端一：仓库根目录，默认 dev；IDEA 也可直接运行主类
mvn -f backend/pom.xml spring-boot:run
```

```bash
# 终端二
cd frontend
npm ci
npm run dev
```

首页：http://127.0.0.1:5173 · 应用健康：http://127.0.0.1:8080/api/v1/health · 数据库健康：http://127.0.0.1:8080/actuator/health

首次启动/重启后，预警需至少 6 根新实时 K 线预热。前后端仅监听本机；本机接口保护不等于完整认证，不直接开放公网。

## Telegram / AI 接入

1. 设置页填写配置，先保持关闭，确认保存至后端。Token/API Key 使用 AES-GCM 加密，不回传明文。
2. 明确确认后执行 Telegram 连接/测试消息、AI 连接/回复测试；即使自动功能关闭，手动测试也会真实联网，可能发送消息或产生费用。
3. 确认接收人后启用 Telegram，后续新事件按等级发送。**只保存配置仍为 MOCK；启用后显示 Telegram + MOCK，模拟记录不会自动隐藏或删除。** `SENT` 表示 Telegram 接受，`SIMULATED` 仅为模拟。
4. 启用手动 AI 分析后，在事件详情确认发送该事件证据并生成报告；模型输出需人工核实。

开发主密钥默认首次保存时创建于 `~/.ai2b/integration.key`（0600），须与数据库分别备份。生产 profile 必须注入稳定的 `AI2B_MASTER_KEY`，通过 `mvn -f backend/pom.xml -Pprod clean package` 排除 dev 配置，再显式激活 prod。

默认模型白名单为 `api.openai.com`，其他服务商通过后端 `AI2B_AI_ALLOWED_HOSTS` 配置；本地模型须显式设置 `AI2B_ALLOW_LOCAL_AI=true`。当前不支持任意地址或系统代理。完整步骤、安全及故障边界见[集成说明](docs/INTEGRATIONS.md)。

## 后续开发与接入

1. **先验真实环境：** MySQL V4/V5、主密钥备份恢复、长期采集、用户确认的 Telegram/AI 联调。
2. **再补安全与运维：** 完整认证、通知人工核对/重试、队列监控、部署与恢复；MOCK 可选隐藏/关闭仍是待开发项。
3. **完善 P0：** 自选、ticker/更多币种、规则编辑与版本切换、RVOL/OI/资金费率规则及完整业务验收。
4. **按需扩展：** Telegram Bot 查询、自动/异步 AI 报告、历史效果统计与回测；现货、订单簿、链上数据须先核实授权与成本。

详细优先级、依赖与验收见[后续路线图](docs/ROADMAP.md)。

## 文档

[设计方案](docs/DESIGN.md) · [开发进度与验证](docs/DEVELOPMENT.md) · [前端页面](docs/FRONTEND.md) · [K 线](docs/KLINES.md) · [市场指标](docs/METRICS.md) · [价格预警](docs/ALERTS.md) · [Telegram / AI](docs/INTEGRATIONS.md) · [后续路线图](docs/ROADMAP.md)
