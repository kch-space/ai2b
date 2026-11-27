# ai2b · Crypto Intelligence

面向个人研究的加密货币市场监控、预警与分析平台，让重要信息主动找到人。

首期面向 Binance USDT 永续合约，逐步接入价格、成交量、OI、资金费率与规则预警；不包含自动下单或真实资金管理。

## 当前功能

- Java 21 + Spring Boot 后端、Vue 3 + TypeScript 前端。
- MySQL + Flyway；应用与数据库健康检查。
- Binance 合约元数据同步：启动后自动同步、每小时刷新、手动同步；保留交易状态与精确步长。
- 合约列表：搜索、状态筛选、分页、同步时间及失败提示。

**尚未实现：** 实时价格/K 线采集、自选、规则预警、认证、通知和 AI 分析。合约元数据不等于实时行情。

## Mac 本地启动

需要 JDK 21、Maven 3.8.9+、npm 10+、MySQL 8.0.16+（支持 TLS）。Node.js 支持 22.22.2+（22.x）或 24.15+；当前 Mac 已验证 Node 26.5.0，`frontend/.node-version` 记录该版本。

首次配置：复制 `backend/.env.example` 为 `backend/.env.local`，填写开发数据库连接信息，然后执行 `chmod 600 backend/.env.local`。该文件已被 Git 忽略。已有开发库无需重新初始化；新库按[开发说明](docs/DEVELOPMENT.md#数据库配置与首次初始化)执行初始化。

分别在两个终端运行：

```bash
# 仓库根目录；脚本自动加载 backend/.env.local
./backend/tools/start.sh
```

```bash
cd frontend
npm ci
npm run dev
```

首页：http://127.0.0.1:5173 · 应用健康：http://127.0.0.1:8080/api/v1/health · 数据库健康：http://127.0.0.1:8080/actuator/health

前后端仅监听本机。直接在 IDE 或执行 Maven 启动时，需自行注入环境变量；Spring Boot 不会自动读取 `.env.local`。

[设计方案](docs/DESIGN.md) · [开发与验证说明](docs/DEVELOPMENT.md)
