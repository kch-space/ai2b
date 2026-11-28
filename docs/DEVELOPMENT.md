# 本地开发与验证

更新日期：2026-11-28。当前开发环境为 macOS / zsh，数据库初始化脚本使用 Bash。

## 当前范围与开发进度

截至 2026-11-28，已实现数据采集、M1 价格预警闭环、Telegram 服务端发送与手动 AI 研究。**代码实现、自动测试与真实环境验收分别记录；完整 P0/MVP 尚未通过验收。**

| 模块 | 已交付代码 | 尚待完成/验证 |
| --- | --- | --- |
| 工程/数据库 | Spring Boot、Vue/TS、MySQL、Flyway V1–V5、健康检查 | 真实 MySQL V4/V5、新环境恢复、完整认证/生产部署 |
| 市场数据 | 合约元数据；BTC/ETH 1m K 线、OI/费率/多空比、补录/缺口恢复 | 其他币种、ticker/WS；上游 451 与长期稳定性 |
| 规则/事件 | 固定 v1 5m 急涨急跌、去重/升级/恢复、history/outbox | 规则编辑/切换、RVOL/OI 等规则、自选 |
| Telegram | 加密配置、身份/消息测试、新事件真实队列、429/未知结果处理 | 用户明确确认后的真实送达；Bot 查询、人工重试界面 |
| AI | 连接/回复测试、手动事件研究与报告 | 真实服务商/费用验证；自动异步分析、回测 |
| 前端 | 概览/市场/详情/事件/设置，显示偏好，确认与审计 | MOCK 隐藏/关闭选项；真实数据长期联调 |

后端是单 Maven 模块，按 health / instrument / kline / metrics / alert / integration package 划分。行情使用 Java 21 HttpClient，Telegram/AI 使用受地址/DNS限制的 OkHttp，JdbcTemplate 持久化；前端使用 Vue 与原生 SVG。采集仍按单实例运行。

### 最新验证快照

- 上一轮实际验证：后端 **83 项**、前端 **99 项**测试通过，生产打包与前端 typecheck/build 通过。
- 集成自动测试用 H2/MySQL 兼容模式、模拟 provider 和本机 HTTP；不使用 dev 数据库或真实 Telegram/AI 密钥。
- HTTP/浏览器联调用空 H2 测试库，只验证服务端配置读取、确认弹窗与页面；没有真实发送或模型调用，测试后端已停止。
- 真实 MySQL V4/V5、长期预警与服务商连通性仍待验证；2026-11-27 行情真实验证是历史记录，不能代替新功能验收。
- 本次仅更新文档，没有重新运行测试、执行迁移或改变外部配置。

后续优先级及交付条件见 [路线图](ROADMAP.md)。

## Mac 开发环境

已验证开发环境：Java 21.0.10、Maven 3.8.9、Node.js 26.5.0、npm 11.17.0。

```bash
java -version
mvn -version
node --version
npm --version
```

- JDK 21：`java` 与 Maven 使用的 JDK 都需要是 21。多版本时可在当前终端执行 `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`，并将 `$JAVA_HOME/bin` 放到 PATH 前面。
- Maven 3.8.9+：当前版本已实际通过构建，无需为本项目强制升级；项目不修改全局 Maven 配置。
- Node.js：`package.json` 接受 `>=22.22.2 <23 || >=24.15.0`，包括当前使用的 26.5.0。`frontend/.node-version` 固定已验证版本，供支持该文件的版本管理器使用。
- MySQL 8.0.16+，必须支持 TLS。此前验证的远程开发实例为 MySQL 8.0.27。
- 公开依赖安装与 Binance 元数据同步需要网络；不需要交易所密钥。

## 数据库配置与首次初始化

本机已配置 `backend/src/main/resources/application-dev.yml`，开发启动无需额外设置 DB_* 或币安密钥环境变量；Telegram/AI 按 [集成说明](INTEGRATIONS.md) 单独配置，生产 profile 必须提供 AI2B_MASTER_KEY。新环境在仓库根目录执行一次：

```bash
cp backend/src/main/resources/application-dev.yml.example backend/src/main/resources/application-dev.yml
chmod 600 backend/src/main/resources/application-dev.yml
```

用编辑器在 YAML 中填写实际值：

```yaml
spring:
  datasource:
    url: 'jdbc:mysql://127.0.0.1:3306/crypto_intelligence?sslMode=REQUIRED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true&rewriteBatchedStatements=true&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=10000'
    username: 'your_development_user'
    password: 'your_development_password'
market:
  binance:
    rest-base-url: 'https://fapi.binance.com'
    api-key: 'your_api_key'
    api-secret: 'your_api_secret'
  instruments:
    sync-enabled: true
  klines:
    enabled: true
  metrics:
    enabled: true
```

不要复制示例覆盖本机已有配置。YAML 字符串使用单引号；值内的单引号写成两个单引号。真实 dev 配置已被 Git 忽略，权限为 `0600`。开发打包会包含 dev 配置，仅供本地使用；生产打包使用下文的 `-Pprod clean package`，排除 dev 文件。

`src/main/resources/application.yml` 保存公共配置，设置 `spring.profiles.default: dev`。`application-dev.yml` 保存本地数据库与币安真实值，`application-prod.yml` 保存生产环境配置占位符；测试使用 `src/test/resources/application-test.yml`。IDE 直接运行主类或通过 Maven 启动即可读取 dev，无需设置工作目录或环境变量。初始化工具读取相同的 dev 文件，不加载 `.env.local`。

两个大户多空比接口使用 `market.binance.api-key`；合约信息、K 线与其他指标不发送密钥。`api-secret` 预留给未来账户签名功能，当前业务不用。当前配置已通过独立只读账户调用验证，但产品中的账户签名请求、余额与个人持仓功能尚未实现。真实值只保存在本机后端配置，不要放入前端 `VITE_*`、日志或版本库。

已有库先备份，再正常启动；应用 Flyway 加载 `classpath:db/migration,classpath:db/vendor/{vendor}`，自动应用新增迁移。对于尚不存在的开发库，审阅迁移 SQL 后执行：

```bash
./backend/tools/initialize-database.sh
```

初始化工具检查 MySQL 版本，按需创建 YAML 连接 URL 指定的数据库，应用并校验公共 `db/migration` 下的 V1–V4，输出迁移结果、TLS 状态和会话时区。该工具当前不扫描 vendor 目录；V5 由后续应用启动加载，不能将初始化工具成功视为 V5 已升级。初始化账号需要建库/建表权限；日常运行可使用目标库范围内的账号。该工具支持重复执行，不会清库；非空且没有 Flyway 历史的库不会自动接管。失败后先检查历史，不做自动清理。

连接强制 TLS（`sslMode=REQUIRED`）、会话时区 UTC。当前没有配置 CA 与服务端身份验证；此配置是开发配置。Flyway 禁用 clean 和自动 baseline，已执行的迁移保持原样，后续结构变更新增迁移。V1 为标的 / 检查点，V2 为闭合 K 线，V3 为市场指标 / 指标同步状态，V4 为固定价格规则 / 事件 / outbox，vendor V5 为加密集成配置 / Telegram 队列扩展 / AI 报告。本地连接地址、账号、密码由 dev YAML 显式配置。

## 启动与停止

IDEA 中直接运行 `CryptoIntelligenceApplication`，或在仓库根目录启动后端：

```bash
mvn -f backend/pom.xml spring-boot:run
```

在另一个终端启动前端：

```bash
cd frontend
npm ci
npm run dev
```

终端启动时通过 Ctrl+C 停止；IDEA 启动时使用停止按钮。默认只绑定 `127.0.0.1`：

| 地址 | 用途 |
| --- | --- |
| `http://127.0.0.1:5173` | 首页、合约列表、手动同步 |
| `http://127.0.0.1:5173/#/market/BTCUSDT` | BTC K 线详情；页面内可切换 ETH |
| `http://127.0.0.1:8080/api/v1/health` | 应用可用性，不检查行情或数据库 |
| `http://127.0.0.1:8080/actuator/health` | 包含数据库的汇总健康；数据库故障可返回 503 |

Vite 将 `/api` 代理到 8080，不开放 CORS。5173 已占用会明确报错，不自动切换端口。`npm run preview` 仅用于静态构建预览，没有开发 API 代理。当前未实现认证，保持本机访问；同步接口的自定义请求头只防止跨站表单提交，不等于用户认证。

IDE 直接运行 `CryptoIntelligenceApplication`，未指定 profile 时默认使用 `dev`。也可直接运行：

```bash
cd backend
mvn spring-boot:run
# 本地开发 JAR 同样默认使用 dev
java -jar target/crypto-intelligence-backend-0.1.0-SNAPSHOT.jar
```

Windows PowerShell 同样在 `backend` 目录执行 `mvn spring-boot:run`，读取相同的 YAML；新库初始化执行 `backend/tools/Initialize-Database.ps1`，无需 DB_* 环境变量。Mac 数据库初始化脚本不依赖 PowerShell。

## 合约同步与列表

后端调用 `GET /fapi/v1/exchangeInfo`，默认基址 `https://fapi.binance.com`，本地可通过 dev YAML 的 `market.binance.rest-base-url` 修改为可信的数据源。每条记录要求 `contractType=PERPETUAL`、`quoteAsset=USDT`、`marginAsset=USDT`，保留来源的交易状态，页面可筛选 `TRADING`。

- 使用 `PRICE_FILTER.tickSize` 与 `LOT_SIZE.stepSize`，不把 `pricePrecision` / `quantityPrecision` 当作步长。以 `DECIMAL(38,18)` 入库，API 用普通十进制字符串返回，避免浏览器浮点精度损失。
- 启动 5 秒后同步，每次任务结束后间隔一小时再次同步；dev YAML 的 `market.instruments.sync-enabled: false` 关闭自动任务。外部接口失败不阻止应用启动。
- 手动入口为页面“同步合约”。单实例串行执行，成功/失败后的默认冷却为 60 秒；429/418 遵循不低于 60 秒的整数 `Retry-After`，失败时不立即重试。
- 先完整获取并校验快照，再用同一事务更新合约与 `collector_checkpoint`。相同标的复用 ID 和创建时间；新增合约插入，已有合约更新精度与状态。
- 成功快照未包含的已有合约保留，标记本地状态 `NOT_IN_SNAPSHOT`，不推断交易所已下架；后续重新出现可恢复来源状态。
- 网络错误、空快照、重复标的、格式/精度错误不会覆盖已有数据；保存失败整批回滚，检查点不前移。
- 检查点持久化最近成功时间；本次运行中的尝试时间、错误与冷却在内存中，重启会重置。当前只支持单个后端实例执行同步。
- 页面展示 UTC+8 时间，超过两小时未成功同步提示可能陈旧。失败时展示错误与已有数据，不用模拟合约填充空库。页面通过查询/刷新按钮读取最新结果。
- 元数据不包含实时价格、成交量或合约乘数分析。后续行情模块需要另行实现数据采集与资产单位处理。

### API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/instruments` | `keyword` 为合约名子串、`status` 为原始状态或 `NOT_IN_SNAPSHOT`、`page` 从 1 开始、`size` 默认 50，最大 200 |
| POST | `/api/v1/instruments/sync` | 请求头 `X-AI2B-Action: sync-instruments`；同步执行，返回同步状态 |

列表按 symbol 升序，返回 `items/total/page/size/sync/requestId`。`sync` 含最近成功时间、本次尝试时间、运行状态、错误和可再次尝试时间。接口参数错误返回 400；同步忙碌/冷却返回 429；上游或同步失败返回 503，并携带 `Retry-After` 与可读错误。查询使用参数化 SQL，搜索不将 `_` 视为通配符。

```bash
curl 'http://127.0.0.1:8080/api/v1/instruments?keyword=BTC&status=TRADING&page=1&size=25'
curl -X POST -H 'X-AI2B-Action: sync-instruments' \
  http://127.0.0.1:8080/api/v1/instruments/sync
```

合约元数据模块复用 V1 的两张基础表；K 线模块通过 V2 新增 `market_kline`，无需重新建库。

## 闭合 K 线与图表

本阶段仅处理 BTCUSDT、ETHUSDT 的 Binance USD-M 永续合约闭合 1m K 线。首次补录最近 24 小时；此后每 15 秒检查，从持久化连续检查点续采，保留历史数据。任务异步执行，每页最多 499 根、每轮最多四页；更长停机后的补录分轮完成。失败退避并显示原因，不用模拟行情填补缺口。

详情页支持 1 / 6 / 24 小时区间，显示蜡烛图、基础资产成交量、精确数值与缺失分钟。查询只读数据库；手动补录按钮提交后台任务。dev YAML 的 `market.klines.enabled: false` 仅关闭自动采集。接口、数据口径、事务与恢复语义详见 [K 线采集与接口](KLINES.md)。

## OI、资金费率与多空比

BTC / ETH 详情页已接入六类指标，每项约五分钟采集一次，保存最近 24h 范围的来源记录并保留更早已存历史。OI 数量与名义价值、费率快照与已结算费率、账户比与持仓比分开展示。需要 Key 的请求只发送到币安官方地址；错误、完整度与数据时间按指标独立展示。API 为 GET `/api/v1/instruments/{symbol}/metrics` 与异步 POST `/metrics/sync`，详细口径、恢复范围和限制见 [市场指标说明](METRICS.md)。

## 构建与测试

```bash
# 仓库根目录
mvn -B -ntp -f backend/pom.xml verify

# frontend 目录
npm ci
npm test
npm run build
```

当前测试快照见顶部；历史小节中的 55/53、67/64、88 等为各阶段当时结果。后端自动化测试启用 `test` profile，使用本地 H2（MySQL 模式），不加载 dev YAML，覆盖公共数据源配置且禁用自动同步与采集，不连接远程库或交易所。HTTP 适配器使用本地 HTTP fixture 验证路径、响应解析与限流；集成测试验证幂等、状态更新、缺失保留、事务回滚、精度、分页和输入校验。K 线测试另覆盖闭合过滤、稀疏页、连续检查点、重启恢复、共享退避、单任务调度与时钟偏差。前端覆盖连接状态、列表、筛选分页、精度字符串、图表缺口、方向键查看、区间切换、同步成功/失败、轮询、超时和卸载取消。

产物：`backend/target/crypto-intelligence-backend-0.1.0-SNAPSHOT.jar`、`frontend/dist/`，均忽略。普通构建的开发 JAR 默认使用 dev；生产打包必须使用下面的 `-Pprod clean package`，并在运行时显式启用 prod。浏览器检查记录与截图位于忽略的 `output/playwright/`、`.playwright-cli/`。

## 生产配置与打包

在 `src/main/resources/application-prod.yml` 中维护生产配置，当前数据库与币安配置保留环境变量占位符，部署时提供生产值。生产启动显式使用 `prod`，不会同时加载 dev。本机测试凭据仅保存在 `application-dev.yml`。

```bash
# 仓库根目录；重新构建并从生产 JAR 排除 dev 配置
mvn -B -ntp -f backend/pom.xml -Pprod clean package
java -jar backend/target/crypto-intelligence-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=prod
```

Maven 的 `-Pprod` 控制打包时排除 dev 配置，Spring 的 `--spring.profiles.active=prod` 控制运行时选择生产配置。生产凭据未配置时不会使用本地测试库。

## 历史配置验证（2026-11-28，集成开发前）

配置统一放在 `src/main/resources`。未指定 profile 且清除数据库、币安环境变量后，启动成功使用默认 dev，数据库健康 UP。后端 55 项测试通过，测试仍使用 test profile 和 H2。`-Pprod clean package` 构建成功，检查生产 JAR 确认包含 prod 配置、不含 dev YAML 或真实测试凭据。旧 `backend/config` 目录已移除。

## 历史真实行情验证记录（2026-11-27，Mac）

| 检查 | 结果 |
| --- | --- |
| 后端构建与打包 | JDK 21 / Maven 3.8.9，55 项测试通过，`verify` 成功 |
| 前端测试与构建 | Node 26.5.0，53 项测试通过，类型检查与生产构建通过 |
| 真实 MySQL 启动 | HikariCP 连接成功，Flyway V1 / V2 校验与 V3 迁移成功 |
| 实际 HTTP 健康检查 | `/api/v1/health` 与 `/actuator/health` 均 200 / UP |
| 真实 Binance 同步 | 通过后端 Java HttpClient 获取并写入 659 个 USDT 永续合约；数量随交易所变化 |
| 列表与校验接口 | 返回实际合约、分页与检查点；size=201 返回 400 |
| MySQL 连接参数实测 | MySQL 8.0.27、TLS_AES_256_GCM_SHA384、会话时区 +00:00；659 个标的均唯一 |
| 浏览器实际联调 | 桌面与 390px 移动端显示正常；真实库搜索/状态筛选正常，451 失败时保留已存合约 |
| 真实 K 线补录 | BTC / ETH 最近 24 小时窗口各 1,440 根闭合 K 线，零缺口；后续分钟自动补入 |
| 重启续采 | 两个标的的初始覆盖时间保持不变，连续检查点继续前移，窗口保持完整 |
| K 线浏览器交互 | BTC 1h / 24h 与 ETH 切换正常；手动提交成功及冷却提示均验证；390px 页面无横向溢出，图表可单独横向滚动 |
| 真实市场指标 | BTC / ETH 六类指标均已入库；OI 与三种多空比各 288 点、零缺口，已结算费率各三条；两个大户接口鉴权成功 |
| 指标恢复与页面 | 重启后原始记录保留、新五分钟样本与费率快照自动补入；桌面、390px 手机、结算记录展开与币种切换通过 |

已验证真实请求成功并写入数据；独立探测和后续后台请求也出现过 HTTP 451，当前交易所访问存在不稳定性，尚不能保证持续成功同步。失败后已有 659 条数据和成功检查点保持不变，页面显示错误。不根据单次成功推断网络问题已解决，不切换模拟行情。此前 Windows 下的 NIO 启动问题为历史环境记录，当前 Mac 实际 HTTP 启动已验证。

## M1 最小预警闭环（2026-11-28）

已新增 V4、5m PRICE_SURGE / PRICE_DROP 固定版本规则、事件/history/outbox、mock worker 与事件页。启动方式不变，首次启动/重启后需要至少 6 根新实时 K 线预热；不重播历史信号。具体阈值、实时隔离、API 与边界见 [价格预警](ALERTS.md)。

该阶段本地验证：后端 67 项测试通过，前端 64 项测试通过，前端 typecheck/build 通过。测试数据库为 H2 MySQL 兼容模式，不继承 dev 数据库；mock 未向真实渠道发送。真实 MySQL V4 与长期行情触发仍需运行验证，不宣称完整 P0 验收通过。

## 历史前端工作空间调整（2026-11-28，服务端集成前）

概览仅显示两张重点卡片和最多四条近期预警；全量合约独立为 `/#/markets`，默认每页 10 条。新增 `/#/settings` 显示/Telegram/AI/系统分区。显示偏好可用，集成配置为非敏感本地草稿与格式校验，当时真实测试按钮禁用；密钥仅内存，离开分区清空。**后续服务端集成已替代此草稿模式，见下节。**

该阶段前端 88 项测试、typecheck/build 通过。浏览器检查了概览错误态、AI 与 Telegram 设置，864px 与 390px 表单无页面横向溢出；真实后端未启动，未验证实时行情联调或 Telegram/AI 连接。详见 [前端说明](FRONTEND.md)。
## Telegram / AI 服务端集成（2026-11-28）

新增 V5，服务端密钥 AES-GCM 加密，配置默认关闭；Telegram 新事件 outbox 与真实测试，AI 模型测试和手动事件研究/报告。真实操作由设置/事件页确认，完整环境配置、主密钥备份和安全边界见 [集成说明](INTEGRATIONS.md)。生产 profile 现在需 AI2B_MASTER_KEY。

该集成阶段后端 83 项、前端 99 项测试与构建通过。真实 HTTP + 浏览器使用空 H2 库，只验证配置读取与确认交互，没有真实外部调用；已停止测试后端，未操作 dev MySQL 或真实密钥。MySQL V4/V5 与真实服务商仍需验证。

## 后续接入与开发

1. 先验真实 MySQL V4/V5、主密钥备份恢复、长期采集/预警及用户确认的 Telegram/AI 请求。
2. 补完整认证、通知人工核对与重试、队列/采集监控、部署恢复；远程开放须等认证完成。
3. 完善自选、ticker/更多币种、规则编辑/版本切换、RVOL/OI/费率规则和完整 P0 验收。
4. 按需新增 Bot 查询、更多模型提供方、自动异步 AI、历史统计/回测与其他数据源；分别核实权限、成本和授权。

Telegram 启用后仍同时保留 MOCK；默认隐藏/关闭模拟记录是后续可选开发项，当前没有实现，不删除历史。详细依赖与验收见 [路线图](ROADMAP.md)。
