# 本地开发与验证

## 范围

当前仅初始化两个独立项目：Spring Boot 应用健康接口，Vue 首页及连接检测。没有行情、数据库、交易密钥、认证或通知功能，不调用交易所/通知服务，不包含模拟行情。

后端暂为单 Maven 模块，按实际业务增长拆分。前端只引入 Vue 和基础开发/测试工具；Pinia、组件库和图表按业务需要后续加入。

## 环境

- JDK 21（`java -version` 和 `mvn -version` 均应显示 21）。
- Maven 3.9+；使用已有仓库设置，本项目不修改用户或系统 Maven 配置。
- 推荐 Node.js 22.22.2+（22.x）或 24.15+（24.x），npm 10+。测试工具对 Node 的要求高于仅运行 Vite。
- npm / Maven 需要下载公开依赖；不向外部上传本地文件或公司数据。

本机已确认安装 JDK 21.0.13.1，路径为 `C:\Program Files\Java\jdk-21.0.13.1`，但本次工具会话继承的 JAVA_HOME/PATH 仍指向 Java 8。验证时仅在命令进程内选择已安装 JDK 21，未修改系统或用户环境变量。日常启动前需确认 `java -version` 与 `mvn -version` 都显示 21。

## 启动

在仓库根目录打开两个 PowerShell 终端：

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
- Actuator：`GET http://127.0.0.1:8080/actuator/health`，只暴露健康状态，不暴露内部详情。
- 健康为 UP 仅指应用可用，不能据此认为数据采集/通知健康。
- 页面使用相对 `/api/v1/health`，Vite 开发代理转发到 `127.0.0.1:8080`，不需要开放 CORS。
- 请求超时为 5 秒；连接失败或响应无效时显示错误并可手动重试。
- 默认只绑定 loopback，尚无认证，不能直接暴露到公网或当作生产服务。

如果 5173 已占用，Vite 会明确报错而不自动换端口。后端 8080 与代理目标须保持一致；修改配置需另行确认。

## 构建与测试

```powershell
# 在仓库根目录，需要 JDK 21
mvn -f backend/pom.xml verify

# 在 frontend 目录
npm ci
npm test
npm run build
```

后端测试涵盖健康响应、UTC 时间、不同请求 ID、Actuator 与未开放路径；前端测试涵盖加载、成功、失败重试、超时、卸载取消和响应校验。测试 fixture 明确是模拟应用元数据。

构建产物分别为 `backend/target/crypto-intelligence-backend-0.1.0-SNAPSHOT.jar` 与 `frontend/dist/`，均不纳入 Git。

```powershell
java -jar backend/target/crypto-intelligence-backend-0.1.0-SNAPSHOT.jar
```

`npm run preview` 仅用于静态构建检查，不包含开发服务器 `/api` 代理，不能据此验证前后端连接。部署代理、HTTPS、认证与数据库属于后续工作，不在本次范围。

## 本次验证记录（2026-11-25）

| 检查 | 实际结果 |
| --- | --- |
| 依赖安装 | `npm install --no-audit --no-fund` 成功，已生成并保留 package-lock.json |
| 前端测试 | `npm test`，2 个测试文件、11 项测试通过 |
| 前端构建 | `npm run build`，TypeScript 检查与 Vite 生产构建通过 |
| 前端开发服务 | `npm run dev` 启动成功，首页 HTTP 200 |
| 后端兼容性测试与打包 | 使用本机已有 JDK 17，在当前命令进程临时设置 JAVA_HOME/PATH，执行 `mvn -B -ntp -Djava.version=17 verify`；4 项测试通过并打包成功 |
| 后端真实 HTTP 启动 / 联调 | 未通过：JDK 17 和本次 JDK 21 启动 Tomcat 均出现 `Unable to establish loopback connection` / `Invalid argument: connect`，不能宣称联调成功 |
| Java 21 构建、测试与打包 | 已验证：使用已安装 JDK 21.0.13.1 执行 `mvn -B -ntp verify`，4 项测试通过、BUILD SUCCESS；`javap` 确认应用 class 的 major version 为 65（Java 21） |

此前的 Java 17 兼容性验证产物已由本次 JDK 21 构建重新打包，当前应用字节码已确认是 Java 21。单元/上下文测试成功不等同后端监听成功，也不代表业务功能验收通过。未执行生产部署、真实通知或交易所接入。

### JDK 21 启动问题复核

- 直接使用 `C:\Program Files\Java\jdk-21.0.13.1\bin\java.exe` 启动打包 jar，仍在 Tomcat 的 NIO 初始化时失败。
- 在被 Git 忽略的 `backend/target/LoopbackProbe.java` 中执行仅调用 `Selector.open()` 的最小 Java 诊断，不加载 Spring Boot，也出现相同异常。
- 堆栈指向 Windows NIO Selector / Pipe 的 UnixDomainSockets connect 阶段，说明问题可独立于业务代码复现；具体系统环境原因尚未确定，不能仅据此断言防火墙、安全软件或 JDK 缺陷。
- JDK 21 安装与 Maven 构建已验证；真实 HTTP 服务和前后端联调仍待启动问题解决后验证。临时前端开发服务已停止，未改项目端口、网络协议或系统设置。
