# Safety Gate Service — Backend Demo（阶段二：告警中心 + 处置闭环）

智慧货场 S3「装卸作业安全卡控系统」后端。**模块化单体**（非微服务），database-node / fabric-node 运行同一份 JAR，经 Easegress 对外提供 `/api/v1` API。

## 技术栈（固定，不替换）

- Java 17（Temurin 17）、Spring Boot **3.5.5**、Maven 3.9+
- openGauss 6.0.5（官方 `org.opengauss:opengauss-jdbc` 6.0.0，HikariCP + JdbcTemplate，**本阶段不引入 ORM、不建业务表**；注意该驱动内部类名为 `org.postgresql.Driver`、URL 前缀 `jdbc:postgresql://`）
- Kvrocks 2.16.0（Redis 协议 / Lettuce，幂等去重）
- RocketMQ 5.5.0（官方 remoting SDK 独立 Adapter，不使用第三方 starter，本阶段只连不发业务消息）
- openGemini 1.5.2（JDK HttpClient 调 `/ping`，本阶段不设计时序 Schema）
- springdoc-openapi（Swagger UI）、Actuator（health/prometheus）

## 目录结构

```
backend/
├── pom.xml
├── .mvn/                         # 工程内 Maven 配置（jvm.config/settings.xml）
├── src/main/java/com/bproject/safety/
│   ├── SafetyApplication.java          # 启动类
│   ├── common/
│   │   ├── error/                      # 统一错误结构 + GlobalExceptionHandler
│   │   ├── web/TraceIdFilter.java      # X-Trace-Id / MDC / 访问日志
│   │   └── idempotency/                # Kvrocks 幂等服务
│   ├── config/                         # CORS、OpenAPI
│   ├── controller/                     # health / auth / meta（本阶段仅这些端点）
│   ├── infrastructure/
│   │   ├── database/                   # openGauss SELECT 1 探针
│   │   ├── cache/                      # Kvrocks PING/SET/GET/TTL 探针
│   │   ├── mq/                         # RocketMQ Adapter + 探针
│   │   ├── timeseries/                 # openGemini Client(/ping) + 探针
│   │   └── health/                     # 就绪聚合
│   ├── module/
│   │   └── alert/                       # 阶段二：告警中心（model/repository/service/web/seed/dto）
│   └── support/                        # Demo 用户、字典 Seed
├── src/main/resources/
│   ├── application.yml                 # 公共配置（环境变量占位）
│   ├── application-dev.yml             # 本地开发（无基础设施也能启动）
│   ├── application-server.yml          # 服务器（全部来自环境变量）
│   └── logback-spring.xml
├── src/test/                           # 无需真实基础设施即可 mvn test
└── ../deploy/backend/                  # systemd 与 env 模板
```

## 本地开发

```powershell
# Windows（工程内隔离工具链示例）
$env:JAVA_HOME="<project>/.tools/jdk-17.0.20.1+1"
$env:PATH="$env:JAVA_HOME/bin;<project>/.tools/apache-maven-3.9.16/bin;$env:PATH"

mvn spring-boot:run                      # 默认 dev profile
# 或
mvn clean package
java -jar target/safety-gate-service.jar
# 服务器 profile
java -jar target/safety-gate-service.jar --spring.profiles.active=server
```

dev profile 下 openGauss/Kvrocks 未启动时应用仍可启动：`/health/live=UP`，`/health/ready` 中对应组件为 DOWN，RocketMQ/openGemini 默认 DISABLED。

## 本阶段端点

| 端点 | 说明 |
|---|---|
| `GET /health/live` | 存活：`{"status":"UP"}` |
| `GET /health/ready` | 就绪：聚合 application/database/cache/rocketmq/openGemini，任一 DOWN 返回 503 |
| `GET /api/v1/auth/me` | Demo 当前用户（成功响应**直接返回业务对象**，不做 code/data 包装） |
| `GET /api/v1/meta/dictionaries?keys=areas,teams,assignees` | 基础字典，keys 可省 |
| `GET /actuator/health`、`/actuator/prometheus` | Actuator（Categraf 抓取，前端不依赖） |
| `GET /swagger-ui.html` | Swagger UI；`/v3/api-docs` OpenAPI 描述 |

### 告警中心（阶段二，内存 Demo 数据）

| 端点 | 说明 |
|---|---|
| `GET /api/v1/alerts/metrics` | 顶部指标（total/pending/active/severe/urgent/closed，实时计算） |
| `GET /api/v1/alerts` | 列表：keyword/risk(level)/status/area/eventType(type)/source/assignee(owner)/timeRange/from/to/page/pageSize |
| `GET /api/v1/alerts/{id}` | 详情：证据（4 类联合）、联动 7 步、时间线、处置记录 |
| `POST /api/v1/alerts/{id}/confirm` | 待确认 → 待派单 |
| `POST /api/v1/alerts/{id}/assign` | 待派单 → 待处理（责任人/优先级/时限，写 slaDeadline ISO-8601） |
| `POST /api/v1/alerts/{id}/start` | 待处理 → 处理中 |
| `POST /api/v1/alerts/{id}/treatment` | 处理中 → 待复核（严重/紧急必须再 review） |
| `POST /api/v1/alerts/{id}/review` | 待复核 → 已关闭（清空 SLA） |
| `POST /api/v1/alerts/{id}/review-reject` | 待复核 → 处理中 |
| `POST /api/v1/alerts/{id}/transfer` | 转派（状态不变） |
| `POST /api/v1/alerts/{id}/escalate` | 升级风险等级，记录 upgradedFrom |
| `POST /api/v1/alerts/{id}/takeover` | 人工接管（仅记录，不连真实 PLC） |
| `POST /api/v1/alerts/{id}/linkage` | 联动演示：`{"mode":"success"}` / `{"mode":"fail"}` |

- 存储抽象 `AlertRepository`，本地实现 `InMemoryAlertRepository`（启动自动灌入 12 条与前端 Mock 一致的种子，无需数据库）；服务器联调时新增 openGauss 实现替换即可，Controller→Service→Repository 分层不变。
- 非法状态流转统一 **409 STATE_CONFLICT**；每次写操作由后端追加时间线（旧 active 节点收敛为 done），刷新后仍存在。
- 本地无 Kvrocks 时幂等降级为直接执行（重复提交由状态机 409 兜底）；服务器接入 Kvrocks 后相同 `Idempotency-Key` 回放首次结果、不重复写时间线。

## 公共约定

- **TraceId**：请求带 `X-Trace-Id` 则沿用（限字母数字-_，≤64），否则生成 UUID；写入 MDC 并经响应头 `X-Trace-Id` 返回，错误体 `traceId` 与其一致。
- **幂等**：非 GET 请求的 `Idempotency-Key` 以 `idempotency:{key}` 存入 Kvrocks（默认 TTL 600s），双实例共享；Kvrocks 故障时降级放行。
- **错误结构**：`{ "code": "STATE_CONFLICT", "message": "...", "traceId": "...", "details": {}, "timestamp": "ISO-8601" }`，覆盖 400/401/403/404/405/409/422/500，不向前端返回堆栈。
- **时间**：后端统一返回 ISO-8601 带时区（`OffsetDateTime`），展示格式由前端处理。
- **CORS**：由 `CORS_ALLOWED_ORIGINS` 控制，不使用 `*`。

## 部署

见 `../deploy/backend/safety-control-backend.service.example` 与 `backend.env.example`。本工程不会自动修改 `/etc/systemd/system`。

## 本阶段故意不实现

AI/人员/围栏/设备/规则/统计/运维等其余业务接口与数据库表、openGauss 版 AlertRepository（当前仅内存实现）、WebSocket 业务消息、JWT/OAuth2/RBAC、JPA/MyBatis 选型、Alert 最终领域模型与正式状态机、真实 PLC/联动控制、真实业务 Topic 与消息发送。前端 Mock 暂未删除，留待下一阶段前后端联调。
