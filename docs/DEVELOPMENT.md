# 本地开发与验证

## 范围

当前已完成 Spring Boot 健康接口、Vue 首页与本地连接联调，并在用户授权后接入 MySQL、HikariCP 与 Flyway。测试库 `crypto_intelligence` 已创建，首版迁移建立 `instrument`、`collector_checkpoint` 两张基础表及 Flyway 历史表；尚无业务数据、行情采集、规则、交易密钥、认证或通知功能，不调用交易所/通知服务。

后端暂为单 Maven 模块，按实际业务增长拆分。前端只引入 Vue 和基础开发/测试工具；Pinia、组件库和图表按业务需要后续加入。

## 环境

- JDK 21（`java -version` 和 `mvn -version` 均应显示 21）。
- Maven 3.9+；使用已有仓库设置，本项目不修改用户或系统 Maven 配置。
- MySQL 8.0.16+（本次测试实例为 8.0.27），需支持 TLS；迁移账号拥有目标库建表权限。
- 推荐 Node.js 22.22.2+（22.x）或 24.15+（24.x），npm 10+。测试工具对 Node 的要求高于仅运行 Vite。
- npm / Maven 需要下载公开依赖；不向外部上传本地文件或公司数据。

此前验证已确认本机安装 JDK 21.0.13.1，路径为 `C:\Program Files\Java\jdk-21.0.13.1`；当时工具会话继承的 JAVA_HOME/PATH 指向 Java 8。此前验证仅在命令进程内选择已安装 JDK 21，未修改系统或用户环境变量。用户本次确认启动成功，但未提供新的环境版本输出，不能据此认定所有终端的 Java 配置均已调整。日常启动前仍需确认 `java -version` 与 `mvn -version` 都显示 21。

## 启动

在仓库根目录打开两个 PowerShell 终端。先在后端终端设置下方数据库环境变量；已有测试库无需再次创建：

```powershell
mvn -f backend/pom.xml spring-boot:run
```

```powershell
cd frontend
npm ci
npm run dev
```

- 前端：http://127.0.0.1:5173，仅本机监听。
- 后端：`GET http://127.0.0.1:8080/api/v1/health`，返回 status、service、UTC timestamp、requestId。
- Actuator：`GET http://127.0.0.1:8080/actuator/health`，包含数据源健康检查，只暴露汇总状态，不暴露内部详情；数据库故障时可返回 DOWN / HTTP 503。
- 健康为 UP 仅指应用可用，不能据此认为数据采集/通知健康。
- 页面使用相对 `/api/v1/health`，Vite 开发代理转发到 `127.0.0.1:8080`，不需要开放 CORS。
- 请求超时为 5 秒；连接失败或响应无效时显示错误并可手动重试。
- 默认只绑定 loopback，尚无认证，不能直接暴露到公网或当作生产服务。

如果 5173 已占用，Vite 会明确报错而不自动换端口。后端 8080 与代理目标须保持一致；修改配置需另行确认。

## 数据库配置与首次初始化

连接由当前进程环境变量注入，仓库不保存真实地址或凭据。`backend/.env.example` 仅为占位说明，Spring Boot 不会自动加载 `.env`；IDE 启动时也需设置环境变量。

```powershell
# 在运行后端的同一终端执行；密码输入不回显，也不进入命令历史。
$env:DB_HOST = Read-Host 'MySQL 主机'
$env:DB_PORT = '3306'
$env:DB_NAME = 'crypto_intelligence'
$env:DB_USERNAME = Read-Host 'MySQL 用户'
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'MySQL 密码' -AsSecureString)).Password

# 正常启动：Flyway 校验并应用尚未执行的迁移。
mvn -f backend/pom.xml spring-boot:run
```

- 必须提供 `DB_USERNAME`、`DB_PASSWORD`；不设置真实密码默认值。主机默认本机、端口默认 3306、库名默认 `crypto_intelligence`。
- JDBC 强制 TLS（`sslMode=REQUIRED`），不会降级明文；当前未配置服务端证书身份验证，生产连接需另行评估 CA 与 `VERIFY_IDENTITY`，不得将测试配置视为生产安全验收。
- JDBC 会话时区强制 UTC，字符编码 UTF-8；新库使用 `utf8mb4` / `utf8mb4_bin`，标的身份按大小写精确去重，金额/数量精度为 `DECIMAL(38,18)`。
- Flyway 启动校验 checksum，不自动 baseline，禁用 clean。已执行的迁移不得修改；后续变更新增 V2 等版本，并按配置/数据库变更流程二次确认。
- 没有自动创建数据库的应用配置，避免日常运行账号必须拥有全局建库权限；本次已授权创建的测试库可以直接启动。

对于**另一个尚不存在且已获授权的开发库**，先审阅 SQL，再在配置好环境变量和 JDK 21 的终端显式执行：

```powershell
.\backend\tools\Initialize-Database.ps1
```

该工具使用项目运行时依赖，无需安装 MySQL 命令行客户端；检查 MySQL 版本与目标名，执行 `CREATE DATABASE IF NOT EXISTS`，随后应用并验证 Flyway 迁移。重复执行对已有库不重新建表；非空且无 Flyway 历史的库不自动接管。失败时停止，不自动删除/回滚数据库；MySQL DDL 可能部分生效，需审查历史后再处理，不能直接重试或清库。

账号建议拆分为迁移账号和仅限目标库的运行账号；本次按授权使用用户提供的测试账号，未创建新账号或调整权限。日常使用结束后可清除当前终端凭据（不修改系统环境变量）：

```powershell
Remove-Item Env:\DB_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:\DB_USERNAME -ErrorAction SilentlyContinue
```

## 构建与测试

```powershell
# 在仓库根目录，需要 JDK 21
mvn -f backend/pom.xml verify

# 在 frontend 目录
npm ci
npm test
npm run build
```

后端 8 项测试涵盖健康响应、UTC 时间、不同请求 ID、Actuator、未开放路径，以及迁移幂等、唯一键和数值精度/约束。自动化测试使用 `test` profile 的本地 H2（MySQL 模式），覆盖凭据与数据源，不连接远程测试库；H2 不能替代真实 MySQL 兼容性验收。本次另行在已授权的 MySQL 实例验证迁移与 Spring 数据源。前端测试涵盖加载、成功、失败重试、超时、卸载取消和响应校验，fixture 明确是模拟应用元数据。

构建产物分别为 `backend/target/crypto-intelligence-backend-0.1.0-SNAPSHOT.jar` 与 `frontend/dist/`，均不纳入 Git。

```powershell
java -jar backend/target/crypto-intelligence-backend-0.1.0-SNAPSHOT.jar
```

`npm run preview` 仅用于静态构建检查，不包含开发服务器 `/api` 代理，不能据此验证前后端连接。部署代理、HTTPS、认证与行情持久化属于后续工作；本次数据库仅接入基础表。

## 验证记录（2026-11-25）

| 检查 | 实际结果 |
| --- | --- |
| 依赖安装 | `npm install --no-audit --no-fund` 成功，已生成并保留 package-lock.json |
| 前端测试 | `npm test`，2 个测试文件、11 项测试通过 |
| 前端构建 | `npm run build`，TypeScript 检查与 Vite 生产构建通过 |
| 前端开发服务 | `npm run dev` 启动成功，首页 HTTP 200 |
| 后端兼容性测试与打包 | 使用本机已有 JDK 17，在当前命令进程临时设置 JAVA_HOME/PATH，执行 `mvn -B -ntp -Djava.version=17 verify`；4 项测试通过并打包成功 |
| 后端真实 HTTP 启动 / 联调 | 用户确认通过，并提供首页截图：显示“后端服务已连接”、服务名 `crypto-intelligence-backend`、检查时间与请求 ID。此前启动异常见下方历史记录；本次未由工具重新发起 HTTP 检查 |
| Java 21 构建、测试与打包 | 已验证：使用已安装 JDK 21.0.13.1 执行 `mvn -B -ntp verify`，4 项测试通过、BUILD SUCCESS；`javap` 确认应用 class 的 major version 为 65（Java 21） |

此前的 Java 17 兼容性验证产物已由当时的 JDK 21 构建重新打包，当时已确认应用字节码为 Java 21。单元/上下文测试成功不等同后端监听成功，也不代表业务功能验收通过。未执行生产部署、真实通知或交易所接入。

### 历史记录：JDK 21 启动问题复核

- 此前直接使用 `C:\Program Files\Java\jdk-21.0.13.1\bin\java.exe` 启动打包 jar，在 Tomcat 的 NIO 初始化时失败。
- 在被 Git 忽略的 `backend/target/LoopbackProbe.java` 中执行仅调用 `Selector.open()` 的最小 Java 诊断，不加载 Spring Boot，也出现相同异常。
- 堆栈指向 Windows NIO Selector / Pipe 的 UnixDomainSockets connect 阶段，说明问题可独立于业务代码复现；具体系统环境原因尚未确定，不能仅据此断言防火墙、安全软件或 JDK 缺陷。
- 此前已验证 JDK 21 安装与 Maven 构建，但当时真实 HTTP 服务和前后端联调未通过；当时临时前端开发服务已停止，未改项目端口、网络协议或系统设置。此条仅描述此前验证，不代表用户本次运行状态。

### 本地联调成功补充（用户确认）

- 2026-11-25 用户确认前后端已正常启动联调，首页截图显示“后端服务已连接”。
- 截图中的服务为 `crypto-intelligence-backend`，检查时间为 `2026年11月25日 16:25:44`（UTC+8），并显示请求 ID；结合现有前端代码，说明页面已收到并通过健康响应格式校验。
- 当前基础健康接口联调不再作为下一阶段的阻塞项。此前 Windows NIO loopback 异常保留为历史记录；具体修复步骤、根因和本次启动命令未提供，不作推断。
- 本次更新依据用户确认及截图，未重新运行构建、自动化测试、独立健康接口或 Actuator 检查；上表构建与测试结果为此前记录。
- 行情、数据库、规则、通知和认证仍未接入；基础联调通过不等同完整 M0 或 P0 验收通过。


### MySQL / Flyway 接入验证（2026-11-25）

| 检查 | 实际结果 |
| --- | --- |
| 授权范围 | 用户二次确认允许增加 MySQL/Flyway 依赖、连接配置、新建测试库并执行迁移 |
| 连通性与版本 | 测试库 3306 端口可达；MySQL 8.0.27 |
| 新库与首版迁移 | `crypto_intelligence` 创建成功；V1 成功，存在 `instrument`、`collector_checkpoint`、`flyway_schema_history` |
| 连接保护与时区 | TLS cipher `TLS_AES_256_GCM_SHA384`；会话时区 `+00:00` |
| 后端构建与测试 | JDK 21.0.13.1，`mvn -B -ntp -f backend/pom.xml verify`，8 项测试通过、BUILD SUCCESS |
| Spring 数据库装配 | 使用默认配置启动非 Web Spring 上下文，HikariCP 连接成功，数据源健康 UP |
| 校验与重复迁移 | `Flyway.validate()` 成功，重复 `migrate()` 应用 0 项迁移；两张业务基础表均为 0 行 |
| 本轮范围之外 | 未启动新的 HTTP 监听、未重启用户现有服务、未重跑前端测试；未实现认证、采集、规则、通知或创建专用数据库账号 |

本次数据库上下文验证使用被 Git 忽略的 `backend/target/DatabaseContextProbe.java`，验证后上下文与连接池已关闭；不是生产服务，也不代表新版 HTTP/前后端联调已再次验证。之前用户确认的健康接口联调记录仍保留。重启到新版后端前需在启动终端配置数据库环境变量。

## 下一阶段准备（M0 进行中）

按 [设计方案](DESIGN.md#10-实施里程碑与验收) 推进，先补齐 M0 剩余基座，再实现 M1 最小预警闭环。数据库部分已获用户二次确认并完成，认证与业务闭环仍待实施；本节规划不构成新增配置变更、外部数据源调用或真实通知授权。

### 实施顺序

1. **确认实施范围与环境**：确认 MySQL 版本、实例是否可用、数据库权限、单用户认证方案；明确首批标的、历史补录范围及规则参数。真实凭据不写入文档或提交 Git。
2. **补齐持久化与认证基座**：按设计引入数据库迁移、参数校验与单用户认证，验证迁移和受保护接口；保持本地监听。涉及依赖、数据库连接或其他配置变更，执行前须向用户二次确认。
3. **实现 M1 最小闭环**：合约元数据、闭合 1m K 线、5m 价格规则、事件/history/outbox、Web 事件页和 mock 通知。先用明确标记的测试 fixture 验证规则、去重与事务，不把模拟数据当作真实行情。
4. **验证真实行情采集**：经确认后核实 Binance 公开行情接口的连通性、限流、时间与字段口径，再接入采集与历史补偿；不需要交易密钥，不上传本地或公司内部数据。数据不可用时明确展示缺失或错误状态。
5. **验收与更新记录**：验证重复输入不重复建事件、历史补录不误报、升级/恢复/重启行为、Web 事件展示及前后端构建测试。mock 成功不能视为真实通知送达；真实渠道和消息测试另行授权。

### 开始前待确认

- [ ] 是否按“补齐 M0 → M1 最小闭环”推进，以及本轮具体交付范围。
- [x] MySQL 实例、版本与开发数据库准备情况；连接配置变更及新建 `crypto_intelligence` 已获二次确认并验证。
- [ ] 单用户认证方式、首批标的、补录窗口及初始规则阈值。
- [ ] 公开行情调用范围；首轮通知仅使用 mock，不发送真实消息。
