# ai2b · Crypto Intelligence

面向个人研究的加密货币市场监控、预警与分析平台，让重要信息主动找到人。

首期面向 Binance USDT 永续合约，逐步接入价格、成交量、OI、资金费率与规则预警；不包含自动下单或真实资金管理。

## 当前功能

- Java 21 + Spring Boot 后端、Vue 3 + TypeScript 前端。
- MySQL + Flyway；应用与数据库健康检查。
- Binance 合约元数据同步：启动后自动同步、每小时刷新、手动同步；保留交易状态与精确步长。
- 合约列表：搜索、状态筛选、分页、同步时间及失败提示。
- BTCUSDT / ETHUSDT 闭合 1m K 线：首次补录最近 24 小时，后台持续采集、失败退避、重启续采和缺口补齐。
- 合约详情页：1 / 6 / 24 小时蜡烛图与成交量、精确原始值、数据缺口与采集状态、手动补录。
- BTC / ETH 市场指标：OI 数量与名义价值、标记价与资金费率快照、已结算费率、全市场账户 / 大户账户 / 大户持仓多空比，含 24h 趋势与独立采集状态。

**尚未实现：** 未闭合实时价格、其他币种行情、自选、规则预警、认证、通知和 AI 分析。

## Mac 本地启动

需要 JDK 21、Maven 3.8.9+、npm 10+、MySQL 8.0.16+（支持 TLS）。Node.js 支持 22.22.2+（22.x）或 24.15+；当前 Mac 已验证 Node 26.5.0，`frontend/.node-version` 记录该版本。

本机数据库与币安密钥已配置在 `backend/src/main/resources/application-dev.yml`，直接启动即可。新环境复制同目录的 `application-dev.yml.example` 为 `application-dev.yml`，填写实际值并执行 `chmod 600 backend/src/main/resources/application-dev.yml`。真实 dev 配置已被 Git 忽略；生产打包使用 `-Pprod clean package` 排除该文件。已有开发库无需重新初始化；新库按[开发说明](docs/DEVELOPMENT.md#数据库配置与首次初始化)执行初始化。

IDEA 中直接运行 `CryptoIntelligenceApplication` 即可启动后端。也可分别在两个终端运行：

```bash
# 仓库根目录；默认使用 dev 配置
mvn -f backend/pom.xml spring-boot:run
```

```bash
cd frontend
npm ci
npm run dev
```

首页：http://127.0.0.1:5173 · 应用健康：http://127.0.0.1:8080/api/v1/health · 数据库健康：http://127.0.0.1:8080/actuator/health

前后端仅监听本机。`src/main/resources/application.yml` 保存公共配置并默认使用 `dev`，IDE 直接运行主类即可；Maven 启动使用 `mvn -f backend/pom.xml spring-boot:run`。生产配置在同目录 `application-prod.yml`，通过 `--spring.profiles.active=prod` 切换。

[设计方案](docs/DESIGN.md) · [开发与验证说明](docs/DEVELOPMENT.md) · [K 线采集与接口](docs/KLINES.md) · [市场指标](docs/METRICS.md)
