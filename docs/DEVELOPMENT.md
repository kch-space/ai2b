# 本地开发与验证

更新日期：2026-11-27。当前开发环境为 macOS / zsh，项目脚本使用 Bash。

## 当前范围

已实现健康检查、MySQL/Flyway 基础表、Binance USDT 永续合约元数据同步与列表页。未接入实时价格/K 线、OI、资金费率、规则、通知、认证或 AI 分析。

后端为单 Maven 模块，按 package 划分健康检查与合约模块；通过 Java 21 HttpClient 访问交易所、JdbcTemplate 持久化。前端仅使用 Vue 与基础开发工具，暂不增加状态库或组件库。

## Mac 开发环境

本次验证：Java 21.0.10、Maven 3.8.9、Node.js 26.5.0、npm 11.17.0。

```bash
java -version
mvn -version
node --version
npm --version
```

- JDK 21：`java` 与 Maven 使用的 JDK 都需要是 21。多版本时可在当前终端执行 `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`，并将 `$JAVA_HOME/bin` 放到 PATH 前面。
- Maven 3.8.9+：当前版本已实际通过构建，无需为本项目强制升级；项目不修改全局 Maven 配置。
- Node.js：`package.json` 接受 `>=22.22.2 <23 || >=24.15.0`，包括当前使用的 26.5.0。`frontend/.node-version` 固定已验证版本，供支持该文件的版本管理器使用。
- MySQL 8.0.16+，必须支持 TLS。本次远程开发实例为 MySQL 8.0.27。
- 公开依赖安装与 Binance 元数据同步需要网络；不需要交易所密钥。

## 数据库配置与首次初始化

在仓库根目录执行一次：

```bash
cp backend/.env.example backend/.env.local
chmod 600 backend/.env.local
```

用编辑器填写 `backend/.env.local`：

```bash
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=crypto_intelligence
DB_USERNAME='your_development_user'
DB_PASSWORD='your_development_password'
# 可选：关闭后台自动同步，但仍允许页面手动同步
# INSTRUMENT_SYNC_ENABLED=false
```

示例中的值必须替换为实际开发库配置。当前测试库连接信息已配置在本机 `.env.local`，不要重复复制示例覆盖。密码含特殊字符时使用正确的 Shell 引号；该文件被 Bash 加载，只填写可信的环境变量赋值。真实地址、账号和密码不写入文档或 Git。不要使用 `set -x` 输出配置。

`start.sh` 和 `initialize-database.sh` 自动加载该文件；文件中的同名值优先于继承的环境变量。也可不创建文件，直接在启动终端设置 DB_*。IDE 或直接 `mvn` / `java -jar` 启动不会自动读取此文件。

已有库直接启动。对于尚不存在的开发库，审阅 V1 SQL 后执行：

```bash
./backend/tools/initialize-database.sh
```

初始化工具检查 MySQL 版本，按需创建 `DB_NAME`，应用并校验 Flyway 迁移，输出迁移结果、TLS 状态和会话时区。初始化账号需要建库/建表权限；日常运行可使用目标库范围内的账号。该工具支持重复执行，不会清库；非空且没有 Flyway 历史的库不会自动接管。失败后先检查历史，不做自动清理。

连接强制 TLS（`sslMode=REQUIRED`）、会话时区 UTC。当前没有配置 CA 与服务端身份验证；此配置是开发配置。Flyway 禁用 clean 和自动 baseline，已执行的 V1 保持原样，后续结构变更新增迁移。

## 启动与停止

在仓库根目录启动后端：

```bash
./backend/tools/start.sh
```

在另一个终端启动前端：

```bash
cd frontend
npm ci
npm run dev
```

两端均通过 Ctrl+C 停止。默认只绑定 `127.0.0.1`：

| 地址 | 用途 |
| --- | --- |
| `http://127.0.0.1:5173` | 首页、合约列表、手动同步 |
| `http://127.0.0.1:8080/api/v1/health` | 应用可用性，不检查行情或数据库 |
| `http://127.0.0.1:8080/actuator/health` | 包含数据库的汇总健康；数据库故障可返回 503 |

Vite 将 `/api` 代理到 8080，不开放 CORS。5173 已占用会明确报错，不自动切换端口。`npm run preview` 仅用于静态构建预览，没有开发 API 代理。当前未实现认证，保持本机访问；同步接口的自定义请求头只防止跨站表单提交，不等于用户认证。

Windows 仍可在 PowerShell 中设置 DB_*，然后执行 `mvn -f backend/pom.xml spring-boot:run`；新库初始化保留 `backend/tools/Initialize-Database.ps1`。Mac 脚本不依赖 PowerShell。

## 合约同步与列表

后端调用 `GET /fapi/v1/exchangeInfo`，默认基址 `https://fapi.binance.com`，可由 `BINANCE_REST_BASE_URL` 覆盖为可信的数据源。每条记录要求 `contractType=PERPETUAL`、`quoteAsset=USDT`、`marginAsset=USDT`，保留来源的交易状态，页面可筛选 `TRADING`。

- 使用 `PRICE_FILTER.tickSize` 与 `LOT_SIZE.stepSize`，不把 `pricePrecision` / `quantityPrecision` 当作步长。以 `DECIMAL(38,18)` 入库，API 用普通十进制字符串返回，避免浏览器浮点精度损失。
- 启动 5 秒后同步，每次任务结束后间隔一小时再次同步；`INSTRUMENT_SYNC_ENABLED=false` 关闭自动任务。外部接口失败不阻止应用启动。
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

该模块复用 V1 的两张基础表，无需 V2，无需重新建库。

## 构建与测试

```bash
# 仓库根目录
mvn -B -ntp -f backend/pom.xml verify

# frontend 目录
npm ci
npm test
npm run build
```

后端自动化测试使用本地 H2（MySQL 模式），覆盖 DB_* 且禁用自动同步，不连接远程库或交易所。HTTP 适配器使用本地 HTTP fixture 验证路径、响应解析与限流；集成测试验证幂等、状态更新、缺失保留、事务回滚、精度、分页和输入校验。前端覆盖连接状态、列表、筛选分页、精度字符串、同步成功/失败、超时和卸载取消。

产物：`backend/target/crypto-intelligence-backend-0.1.0-SNAPSHOT.jar`、`frontend/dist/`，均忽略。运行 jar 时需自行注入环境变量。浏览器检查记录与截图位于忽略的 `output/playwright/`、`.playwright-cli/`。

## 验证记录（2026-11-27，Mac）

| 检查 | 结果 |
| --- | --- |
| 后端构建与打包 | JDK 21 / Maven 3.8.9，20 项测试通过，`verify` 成功 |
| 前端测试与构建 | Node 26.5.0，23 项测试通过，类型检查与生产构建通过 |
| 真实 MySQL 启动 | HikariCP 连接成功，Flyway V1 checksum 校验通过，无待应用迁移 |
| 实际 HTTP 健康检查 | `/api/v1/health` 与 `/actuator/health` 均 200 / UP |
| 真实 Binance 同步 | 通过后端 Java HttpClient 获取并写入 659 个 USDT 永续合约；数量随交易所变化 |
| 列表与校验接口 | 返回实际合约、分页与检查点；size=201 返回 400 |
| MySQL 连接参数实测 | MySQL 8.0.27、TLS_AES_256_GCM_SHA384、会话时区 +00:00；659 个标的均唯一 |
| 浏览器实际联调 | 桌面与 390px 移动端显示正常；真实库搜索/状态筛选正常，451 失败时保留已存合约 |

已验证真实请求成功并写入数据；独立探测和后续后台请求也出现过 HTTP 451，当前交易所访问存在不稳定性，尚不能保证持续成功同步。失败后已有 659 条数据和成功检查点保持不变，页面显示错误。不根据单次成功推断网络问题已解决，不切换模拟行情。此前 Windows 下的 NIO 启动问题为历史环境记录，当前 Mac 实际 HTTP 启动已验证。

## 下一步

在现有合约列表上，先针对 BTCUSDT、ETHUSDT 实现闭合 1m K 线落库、有限历史补录与缺口恢复，再实现 5m 价格异动规则、事件中心和 mock 通知。认证应在开放远程访问及配置写接口前补齐。
