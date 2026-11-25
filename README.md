# ai2b · Crypto Intelligence

面向个人研究的加密货币市场监控、预警与分析平台，让重要信息主动找到人。

规划监控 Binance USDT 永续合约的价格、成交量、OI 与资金费率，提供规则预警及后续研究分析；不包含自动下单或真实资金管理。

## 项目结构

- `backend/`：Java 21 + Spring Boot + Maven，基础健康接口。
- `frontend/`：Vue 3 + TypeScript + Vite，首页与后端连接检测。
- `docs/`：设计方案与本地开发说明。

**当前状态：** 已创建基础工程；行情、预警、数据库、认证和通知尚未接入。

## 本地启动

需要 JDK 21、Maven 3.9+、Node.js 22.22.2+（22.x）或 24.15+（24.x）、npm 10+。分别在两个终端执行：

```powershell
# 后端（在仓库根目录）
mvn -f backend/pom.xml spring-boot:run
```

```powershell
# 前端（在仓库根目录）
cd frontend
npm ci
npm run dev
```

首页：http://127.0.0.1:5173 · 健康接口：http://127.0.0.1:8080/api/v1/health

[设计方案](docs/DESIGN.md) · [开发与验证说明](docs/DEVELOPMENT.md)
