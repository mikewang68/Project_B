# 01 - 项目目录与系统架构全景

> **模块全称**：智慧货场 S3「装卸作业安全卡控系统」（Safety Control System，简称 SCS）  
> **工程代码路径**：`Project_B/SCS`  
> **文档定位**：全景技术架构、代码组织结构、分层契约与前后端协同拓扑  
> **基线日期**：2026-09-21  

---

## 1. 核心目录树（工程真实结构）

以下目录树经过严格精简，过滤了 `node_modules`、`target`、`dist`、`.git` 等衍生及缓存文件，真实展现当前 SCS 的代码包划分与资产分布：

```text
Project_B/SCS/
├── README.md                                # SCS 独立开发与环境基线说明
├── package.json / pnpm-workspace.yaml       # 前端根依赖与工作空间定义
├── pnpm-lock.yaml                           # 严格锁定的 415 个前端依赖
├── .config.example.json / .config.json      # 本地 Vite 代理配置（可选，已 gitignore）
├── .tools/                                  # 项目隔离工具链（已 gitignore，严禁提交）
│   ├── jdk-17.0.20.1+1/                    # Eclipse Temurin JDK 17 (后端运行时)
│   ├── apache-maven-3.9.16/                 # Maven 3.9.16 构建工具
│   ├── node-v24.18.0-win-x64/               # Node.js 24.18.0 / npm 11.16.0
│   └── pnpm-local/                          # pnpm 10.34.5 独立运行时
│
├── backend/                                 # Spring Boot 3.5.5 模块化单体后端
│   ├── pom.xml                              # Maven POM (Enforcer 校验 Java 17 + Maven 3.9.x)
│   ├── .mvn/settings.xml                    # 隔离 Maven 仓库配置
│   └── src/
│       ├── main/
│       │   ├── java/com/bproject/safety/
│       │   │   ├── SafetyApplication.java   # 后端 Spring Boot 启动入口
│       │   │   ├── common/                  # 公共通用基础设施
│       │   │   │   ├── error/               # ApiException, GlobalExceptionHandler, ErrorCode
│       │   │   │   ├── geo/                 # Polygon2D (多边形 Point-In-Polygon 射线法判定)
│       │   │   │   ├── idempotency/         # IdempotencyService (基于 Kvrocks/Redis SETNX)
│       │   │   │   ├── realtime/            # LiveEventGate, LiveEvent, LiveEventTypes, DomainLivePublisher
│       │   │   │   └── web/                 # WebConfig (CORS, TraceIdFilter)
│       │   │   ├── config/                  # AppConfig, OpenApiConfig, WebSocketConfig
│       │   │   ├── controller/              # 公共基础设施控制器 (Auth, Health, Meta)
│       │   │   ├── infrastructure/          # 生产基础设施就绪探针 (Readiness Probes)
│       │   │   │   ├── database/            # DatabaseProbe (openGauss SELECT 1)
│       │   │   │   ├── cache/               # KvrocksProbe (Redis PING)
│       │   │   │   ├── mq/                  # RocketMqProbe (NameServer 探针)
│       │   │   │   ├── timeseries/          # OpenGeminiProbe, OpenGeminiClient (/ping)
│       │   │   │   └── health/              # InfrastructureProbe, ProbeResult, ReadinessAggregator
│       │   │   ├── module/                  # 9 大核心业务与投影模块
│       │   │   │   ├── ai/                  # AI 视觉违规识别 (AiEventController, AiEventService, AiEventRepository)
│       │   │   │   ├── alert/               # 告警中心处置主链 (AlertController, AlertService, AlertRepository)
│       │   │   │   ├── analytics/           # 统计分析投影 (AnalyticsController, AnalyticsService)
│       │   │   │   ├── collision/           # 设备防碰撞 (CollisionController, CollisionService, CollisionRepository)
│       │   │   │   ├── fence/               # 电子围栏 (FenceController, FenceService, FenceRepository)
│       │   │   │   ├── ops/                 # 运维与边缘自治 (EdgeOpsService, EdgeReplayService, EdgeNode/EventQueue/OpsLog Repo)
│       │   │   │   ├── personnel/           # 人员定位 (PersonnelController, PersonnelService, PersonnelRepository)
│       │   │   │   ├── projection/          # 只读投影服务 (Overview, Screen, Mobile, SafetyProjectionService)
│       │   │   │   └── rule/                # 规则引擎 (RuleController, RuleService, RuleRepository)
│       │   │   └── support/                 # Demo 支持与种子管理
│       │   │       ├── demo/                # DemoFeatureGuard, DemoSeedInitializer, Demo*NumberGenerator
│       │   │       └── masterdata/          # DemoMasterData, DemoDeviceMasterData
│       │   └── resources/
│       │       ├── application.yml          # 全局默认配置 (HikariCP, openGauss, Redis, Actuator)
│       │       ├── application-dev.yml      # 开发 Profile (探针平滑降级, Demo 种子默认开启)
│       │       ├── application-server.yml   # 服务器 Profile (强校验 5000ms, Demo 默认关闭)
│       │       └── db/design/               # 权威 DDL 设计脚本
│       │           ├── openGauss-schema-final-draft.sql  # 25 张表终版 DDL (READY FOR SPIKE)
│       │           └── openGauss-schema-draft.sql        # 历史修订底稿
│       └── test/                            # 32 个自动化测试类 (142 tests 100% 通过)
│           ├── java/com/bproject/safety/
│           │   ├── ApplicationContextSmokeTest.java
│           │   ├── module/*                 # 9 大业务模块集成测试
│           │   └── support/phaseb/          # Phase B 架构硬化审计专用测试套件
│           └── resources/application-test.yml # 测试环境 Profile
│
├── frontend/                                # Vue 3 + TypeScript 严格模式管理端
│   ├── vite.config.ts / tsconfig.json       # 构建与 TS 配置 (别名 @ -> src)
│   ├── vitest.config.ts                     # 前端单测配置 (jsdom 环境)
│   └── src/
│       ├── App.vue / main.ts / router/      # 路由配置 (含 11 个页面与导航看护)
│       ├── adapters/                        # 11 个 ViewModel 与 DTO 隔离纯函数适配器 (*.ts + *.spec.ts)
│       ├── api/                             # 15 个后端 REST API 客户端模块
│       ├── stores/                          # 5 个全局 Pinia Store (auth, live, dictionary, incident, operations)
│       ├── types/                           # 前端严格 TypeScript 类型定义
│       ├── views/                           # 11 个页面级业务视图
│       │   ├── OverviewView.vue             # 首页态势大盘
│       │   ├── PersonnelLocationView.vue    # 人员定位与轨迹
│       │   ├── FenceManagementView.vue      # 电子围栏绘制与下发
│       │   ├── CollisionOverviewView.vue    # 防碰撞态势与六级停机
│       │   ├── AiReviewView.vue             # AI 视频抓拍与复核
│       │   ├── AlertCenterView.vue          # 告警中心全生命周期闭环
│       │   ├── RuleConfigView.vue           # 规则配置与热发布
│       │   ├── AnalyticsView.vue            # 统计趋势图表
│       │   ├── OperationsView.vue           # 边缘节点运维与断网模拟
│       │   ├── SafetyScreenView.vue         # 监控大屏
│       │   └── mobile/                      # 移动端视口 (Home, Alerts, Detail)
│       └── components/                      # 116 个业务通用与专用组件
│
├── deploy/                                  # 服务器三节点部署模板与脚本
│   ├── README.md                            # 部署架构与拓扑说明
│   ├── backend/backend.env.example          # 后端 systemd 环境变量模板
│   ├── easegress/                           # node4/node5 Easegress 2.11 网关配置模板
│   ├── nginx/                               # node6 Nginx 1.24 反代与静态托管模板
│   └── scripts/
│       ├── build-server.sh                  # 服务器环境全量逐字校验与构建脚本
│       └── verify-deployment.sh             # 自动化探针校验脚本
│
└── docs/                                    # 权威架构文档与设计资产
    ├── database-design/                     # 数据库设计全套文档 (ER, 数据字典, DDL 补丁审计)
    ├── demo-architecture-review.md          # 纯只读全局架构审计报告 (Phase B 起点)
    ├── demo-architecture-phase-b5-status.md # Phase B.5 核心缺陷闭环报告
    ├── pre-database-repository-hardening.md # Repository 契约硬化与重构规范
    └── ai-handoff/                          # 【本系列】面向 AI 与新研发交接的完整上下文
```

---

## 2. 技术栈与运行基线

| 维度 | 技术栈与版本 | 说明与代码约束 |
|---|---|---|
| **后端框架** | Spring Boot `3.5.5` / Java `17` | `pom.xml` 配置 Maven Enforcer，强制限制 `[17, 18)` |
| **构建工具** | Apache Maven `3.9.16` | 强制使用项目内 `.tools/apache-maven-3.9.16` |
| **前端框架** | Vue `3.5` + TypeScript 严格模式 | `package.json` 强制 `engines`：Node `<25`、pnpm `<11` |
| **前端工具链** | Vite `7` / Vitest / Vue Router `4` / Pinia `3` | Element Plus `2.11`、ECharts `6.1`、Sass |
| **关系型数据库** | openGauss `6.0.0` (兼容 PostgreSQL 协议) | 官方 JDBC 驱动 `org.opengauss:opengauss-jdbc:6.0.0` |
| **分布式缓存/幂等** | Kvrocks (兼容 Redis 协议) | Spring Data Redis (Lettuce 连接池) |
| **消息中间件** | Apache RocketMQ `5.5.0` | 官方 Remoting Client，用于未来事件总线 |
| **时序数据库** | openGemini (规划) | 承载人员高频轨迹与雷达连续测距，严禁回流 openGauss |
| **反向代理与网关** | Easegress `2.11` + Nginx `1.24` | 三节点分层反代与 WebSocket 转发 |

---

## 3. 模块划分与分层架构

系统后端遵循经典的**模块化单体（Modular Monolith）**设计，各模块具有高内聚性，通过清晰的 Service 接口与 DTO 契约进行协作：

```mermaid
flowchart TD
    subgraph Web_Layer [控制层 (15 Controllers)]
        AC[AlertController]
        AIC[AiEventController]
        CC[CollisionController]
        FC[FenceController]
        PC[PersonnelController]
        RC[RuleController]
        OC[OperationsController / EdgeAutonomyController]
        PRC[Overview / Screen / Mobile / Analytics Controllers]
    end

    subgraph Service_Layer [服务层 (14 Services)]
        AS[AlertService - 唯一安全处置主链]
        AIS[AiEventService]
        CS[CollisionService]
        FS[FenceService]
        PS[PersonnelService]
        RS[RuleService]
        EOS[EdgeOpsService]
        ERS[EdgeReplayService]
        SPS[SafetyProjectionService]
        IS[IdempotencyService]
    end

    subgraph Domain_Event_Layer [实时事件与门控]
        LEG[LiveEventGate - 事务感知事件门]
        DLP[DomainLivePublisher]
        WSH[LiveWebSocketHandler - /ws/live]
    end

    subgraph Repository_Layer [仓储层 (9 Repositories)]
        AR[(AlertRepository)]
        AIR[(AiEventRepository)]
        CR[(CollisionRepository)]
        FR[(FenceRepository)]
        PR[(PersonnelRepository)]
        RR[(RuleRepository)]
        ENR[(EdgeNodeRepository)]
        EQR[(EdgeEventQueueRepository)]
        OLR[(OpsEventLogRepository)]
    end

    AC --> AS
    AIC --> AIS
    CC --> CS
    FC --> FS
    PC --> PS
    RC --> RS
    OC --> EOS & ERS
    PRC --> SPS

    AIS -->|createFromAi / assign| AS
    CS -->|ensureCollisionAlert / upgradeRisk| AS
    PS -->|intrude 越界建单| AS
    ERS -->|createEdgeReplayAlert| AS

    AS --> AR
    AIS --> AIR
    CS --> CR
    FS --> FR
    PC --> PR
    RS --> RR
    EOS --> ENR & OLR
    ERS --> EQR & OLR

    AS & AIS & CS & PS & RS & ERS --> LEG
    LEG --> DLP --> WSH
```

### 核心分层说明：
1. **Web Layer（控制层）**：
   统一路由前缀 `/api/v1`，负责参数校验（JSR-303/380 Bean Validation）、HTTP 响应统一封装、TraceId 链路染色以及基于 `DemoFeatureGuard` 的模拟接口权限拦截。
2. **Service Layer（业务服务层）**：
   负责核心领域规则、状态机推进与跨聚合业务编排。**`AlertService` 作为全局唯一的安全事件闭环处理中枢**，所有感知风险均汇聚于此。
3. **Repository Layer（数据访问层）**：
   当前全部为 `InMemory*Repository` 实现（基于 `ConcurrentHashMap` 与深拷贝机制）。业务服务**仅依赖 Repository 接口**，严禁侵入具体内存或数据库实现细节。
4. **Realtime & Event Layer（实时与事件层）**：
   通过 `LiveEventGate` 保证在多步写操作中“先全部 save 成功，再统一触发 WebSocket 广播”，杜绝半事务脏事件泄露。
5. **Projection Layer（只读投影层）**：
   `OverviewService`、`ScreenService`、`MobileService`、`AnalyticsService` 作为纯粹的无状态投影聚合器，直接从各 Repository 只读聚合生成前端展示 DTO，自身不持久化任何状态。

---

## 4. 前后端协同与适配机制

前端工程不直接使用后端 DTO 渲染界面，而是通过专门设计的 **11 个 Adapter（纯函数转换层）** 隔离前后端模型变动风险：

```text
后端 API (/api/v1/*)  ──>  前端 API Client (src/api/*.ts)
                                     │ (原始后端 DTO)
                                     ▼
                           前端 Adapters (src/adapters/*.ts)
                                     │ (纯函数清洗 / 字典解析 / 视图状态计算)
                                     ▼
                           Pinia Stores / Vue Components (ViewModel)
```

- **统一主数据解析**：前端不内嵌人员/区域硬编码，通过 `/api/v1/meta/dictionaries` 异步加载权威数据字典并注入 `dictionary` store，Adapter 负责将后端 Code 翻译为界面标签。
- **全双工实时响应**：页面初始化调用 REST 获取快照，随后建立 `/ws/live` 原生 WebSocket 连接；当收到 `alert.new`、`collision.changed` 等事件时，Store 与组件无缝局部刷新。
