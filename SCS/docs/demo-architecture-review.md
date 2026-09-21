# 全局 Demo 可扩展性架构审计报告（demo-architecture-review）

- 项目：智慧货场 S3「装卸作业安全卡控系统」（SCS）
- 技术栈：Spring Boot 3.5.5 / Java 17（模块化单体，全量 InMemory Repository）+ Vue3 / TypeScript / Pinia / Element Plus
- 审计性质：**纯只读架构审计**。未修改任何业务代码、前端、DDL、数据库设计、配置、pom/package.json；未连接 openGauss；未执行任何 SQL。
- 审计日期：2026-09-19
- 审计基线：后端 133 个 Java 文件、15 Controller、14 个 `*Service.java`（13 个业务/投影 Service + 1 个公共 IdempotencyService）、9 Repository 接口 + 9 InMemory 实现、3 Seeder；前端 11 视图、116 组件、15 个 api 模块、5 个 Pinia Store、29 个 spec。
- Baseline 验证：后端 `mvn test` **142 tests 全过 BUILD SUCCESS**；前端 `pnpm typecheck` **通过**；`pnpm test` **29 文件 / 117 用例全过**。
- 结论分级：**BLOCKER**（落库前必须解决）/ **HIGH**（Repository 迁移前必须解决）/ **MEDIUM**（多实例/服务器部署前解决）/ **LOW**（产品化阶段解决）；每项同时给修复时机 NOW / BEFORE_REPOSITORY_MIGRATION / BEFORE_SERVER_DEPLOY / LATER。

---

## 1. Executive Summary（总体结论）

**总体评定：READY WITH FIXES（有条件地可以进入数据库阶段）。**

当前 Demo 的核心架构边界是**健康且值得被持久化层继承**的：

1. **Alert 是唯一安全处置主链**。AI / Personnel / Collision / EdgeReplay 四个风险来源全部通过 `AlertService` 的应用方法建单（`createFromAi` / `createRiskAlert` / `createEdgeReplayAlert` / `upgradeRisk`），没有发展出第二套处置状态机；唯一例外是 Analytics 的演示开关 `simulate-surge` 直写 `AlertRepository`（Demo-only 捷径，见 F-04）。
2. **Projection 无状态、无独立权威**。Overview / Screen / Mobile / Analytics 全部从核心 Repository **只读聚合**，没有自己的 Repository、没有写回、没有统计表。
3. **实时事件抽象可替换**。业务 Service 只依赖 `AlertChangeNotifier` / `AiChangeNotifier`（接口）或 `DomainLivePublisher`（单一组件），只有 3 个 WebSocket 实现类接触 `WebSocketSessionRegistry`；未来替换为 Outbox → RocketMQ → fan-out 时业务代码无需改动。
4. **主数据已统一**。`DemoMasterData` 是 Users/Teams/Areas 唯一来源，`/meta/dictionaries` 由其生成，前端派单双弹窗共用同一份 7 人字典、无本地人员 fallback。
5. **错误模型、幂等框架、Clock 注入、健康探针、配置 profile 骨架**均已具备。

但在 openGauss 落地前，有一组**明确的、有限的必修项**（见第 16 节，共 10 项），集中在四类：

- **语义 code 化未完成**：风险等级（一般/预警/严重/紧急）在约 20 处直接以中文字面量做业务判断，且没有与状态常量类对等的 `RiskLevels` 常量；落库会把展示文案冻进查询列与约束。
- **Repository 契约面向可变内存对象**：9 个接口直接暴露可变领域对象、内嵌 List 子实体、`save()` 全量 upsert、存在 `clear()/deleteById()/findMutable()` 等 Demo 方法；`EdgeOpsService`/`EdgeReplayService` 甚至直接依赖 **InMemory 实现类**与强制转型。JDBC 实现无法满足现有契约。
- **跨聚合写操作没有事务边界**：AI confirm/assign、Collision 升级、Edge replay 都在一个业务动作里写两个 Repository + 发两类事件，当前靠 `synchronized` 顺序执行，数据库时代需要事务编排层与 AFTER_COMMIT/Outbox。
- **Demo 能力没有 profile 隔离**：3 个 Seeder 是无条件 `CommandLineRunner`，10+ 个 `simulate*` 端点在 `server` profile 下同样暴露并会灌演示数据——这是**上服务器前的 BLOCKER**，但不阻塞本地落库设计。

这些问题都不需要推倒重来；它们是"把内存单体搬到关系库"必然要补的契约与编排工作，且数据库设计蓝图（`docs/database-design/`）已经为其中绝大多数预留了正确形态。本报告只记录问题与建议顺序，**不做任何修改**。

---

## 2. Current Architecture Map（模块地图）

后端包根 `com.bproject.safety`，分层为 `common`（error / geo / idempotency / realtime / web）、`config`、`controller`（Auth/Health/Meta）、`infrastructure`（database/cache/mq/timeseries 探针与适配）、`module`（9 个业务模块）、`support`（demo 配置、masterdata）。

| 模块 | Controller | Service（写/聚合） | Repository（InMemory） | 关键 Model | 依赖的其他模块 |
|---|---|---|---|---|---|
| Auth | AuthController | DemoUserProperties（support） | — | — | — |
| Meta | MetaController | DemoMasterData（support，@Component） | — | Users/Teams/Areas 记录 | 无 |
| Health | HealthController | InfrastructureProbe、ReadinessAggregator | — | ProbeResult | infrastructure.* |
| Alert | AlertController | **AlertService**（写主链）、AlertMetrics | AlertRepository | DemoAlert、AlertEvidence(sealed 5 型)、TimelineEvent、LinkageStep、TreatmentRecord | Idempotency、DemoUser |
| AI | AiEventController | **AiEventService** | AiEventRepository | DemoAiEvent、AiBox、AiTimelineNode、CameraInfo | **Alert（AlertService）**、Idempotency、DemoUser |
| Personnel | PersonnelController | **PersonnelService** | PersonnelRepository | DemoPersonnel、TrackPoint（即时生成） | **Alert（AlertService）**、Fence（只读）、Polygon2D |
| Fence | FenceController | **FenceService** | FenceRepository | DemoFence、FencePoint、fence.model.EdgeNode | — |
| Collision | CollisionController | **CollisionService** | CollisionRepository | DemoCollisionDevice、PairState（运行态）、DistancePoint、CollisionStep | **Alert（AlertService）** |
| Rule | RuleController | **RuleService** | RuleRepository | DemoRule（含 Version/Param/内嵌 EdgeNode） | — |
| Analytics | AnalyticsController | **AnalyticsService** | 无（读 AlertRepository） | AnalyticsDtos | Alert（读；simulate-surge 直写，见 F-04）、RiskClassifier |
| Overview | OverviewController | **OverviewService** | 无 | OverviewDtos（含 OverviewMap） | Alert/AI/Collision/Fence/Personnel（只读）+ AlertService（仅 demoRisk） |
| Screen | ScreenController | **ScreenService** | 无 | ScreenDtos | SafetyProjectionService |
| Mobile | MobileController | **MobileService** | 无 | MobileDtos | Alert（只读）+ AlertService（accept/arrive） |
| Projection(shared) | — | **SafetyProjectionService**、RiskClassifier | 无 | ProjectionDtos | Alert（只读） |
| Operations | OperationsController | **EdgeOpsService**、OpsInventory（设备台账 @Component） | EdgeNodeRepository、OpsEventLogRepository | DemoEdgeNode、OpsEventLog、RecoveryPhase | Rule（只读）、Alert（metrics 只读）、InfrastructureProbe |
| Edge Autonomy | EdgeAutonomyController | **EdgeReplayService** | EdgeEventQueueRepository | EdgePendingEvent、EdgeLocalLinkage | **Alert（AlertService）** |
| Realtime | （WS /ws/live） | DomainLivePublisher、WebSocketSessionRegistry、LiveWebSocketHandler | — | LiveEvent、LiveEventTypes | — |

前端：5 个 Store —— `auth`（当前用户，全局基础设施）、`live`（WS 状态，全局基础设施）、`dictionary`（字典缓存，全局基础设施）、`incident`（大屏+移动端 Projection 缓存）、`operations`（仅云边链路 UI 模拟态）；15 个 api 模块；11 个 adapter（纯 DTO→ViewModel 映射，均带 spec）；`mock/` 6 个文件（见第 12 节）。

---

## 3. Module Dependency Graph（真实 import / 构造器依赖）

下图依据对各 Service `import com.bproject.safety.*` 与构造器参数的实际扫描绘制（不是印象图）。

```mermaid
flowchart TD
    subgraph 风险感知域
        AI[AiEventService<br/>AI 检测/复核]
        PER[PersonnelService<br/>人员定位/越界]
        COL[CollisionService<br/>防碰撞判定]
        EDGE[EdgeReplayService<br/>断网补传]
    end

    subgraph 处置主链
        ALERT[AlertService<br/>唯一安全处置根]
        ALERTREPO[(AlertRepository<br/>InMemory)]
    end

    subgraph 配置域
        RULE[RuleService]
        FENCE[FenceService]
        RULEREP[(RuleRepository)]
        FENCEREP[(FenceRepository)]
    end

    subgraph 运维域
        OPS[EdgeOpsService]
        QUEUE[(EdgeEventQueueRepository)]
        NODEREP[(EdgeNodeRepository)]
        LOGREP[(OpsEventLogRepository)]
    end

    subgraph 投影域(只读聚合)
        ANA[AnalyticsService]
        OV[OverviewService]
        SCR[ScreenService]
        MOB[MobileService]
        PROJ[SafetyProjectionService]
    end

    subgraph 其他只读仓储
        AIREP[(AiEventRepository)]
        PERREP[(PersonnelRepository)]
        COLREP[(CollisionRepository)]
    end

    AI -->|createFromAi/confirm/assign/start| ALERT
    PER -->|createRiskAlert| ALERT
    PER -.->|只读: PIP 判定| FENCEREP
    COL -->|createRiskAlert/upgradeRisk/linkage/takeover| ALERT
    EDGE -->|createEdgeReplayAlert| ALERT
    OPS -->|metrics 只读 / demoRisk| ALERT
    OPS -.->|只读: 版本对账| RULEREP

    ALERT --> ALERTREPO
    AI --> AIREP
    PER --> PERREP
    COL --> COLREP
    RULE --> RULEREP
    FENCE --> FENCEREP
    OPS --> NODEREP
    OPS --> LOGREP
    EDGE --> QUEUE
    EDGE --> LOGREP

    ANA -->|只读聚合| ALERTREPO
    PROJ -->|只读聚合| ALERTREPO
    SCR --> PROJ
    MOB -->|只读聚合| ALERTREPO
    OV -->|只读聚合| ALERTREPO
    OV -->|只读聚合| AIREP
    OV -->|只读聚合| COLREP
    OV -->|只读聚合| FENCEREP
    OV -->|只读聚合| PERREP
    OV -->|仅 simulateRisk 演示开关| ALERT

    ANA -.->|F-04 Demo捷径: simulate-surge 直写 save/delete + 自发 ALERT_NEW| ALERTREPO

    classDef bad stroke:#F56C6C,stroke-width:2px;
    class ANA bad;
```

**依赖方向审计结论：**

- **无 Controller → Repository 注入**（AlertController 虽 import `AlertRepository`，但仅引用其嵌套类型 `AlertPageResult` 作返回签名，构造器只注入 AlertService——可接受，但 JDBC 化时建议把分页结果类型移出 Repository 接口）。
- **无 Service → 其他模块 Repository 的写操作**，唯一例外是 `AnalyticsService.injectSurge`（F-04，Demo-only）。
- PersonnelService 注入 `AlertRepository`（:42）与 `FenceRepository`（:41），但全部为**只读**：`alertRepository.findAll()` 仅用于派生 `activeAlertIds`（:86）与人员告警列表（:120），`fenceRepository.findAll()` 用于 PIP 选围栏（:167）。属合理的 Projection Read，但 JDBC 化后建议改为显式查询方法而非 `findAll()` 全表扫。
- EdgeOpsService 注入 `RuleRepository.findAll()`（:777）做版本对账，只读，合理。
- 未发现 Repository → Service、Model → Service、Projection 写业务数据、循环依赖。

---

## 4. Domain Boundaries（领域边界评价）

| 领域 | 边界评价 | 关键证据 | 主要问题 |
|---|---|---|---|
| **Alert** | **清晰，是唯一处置根**。8 个状态、Timeline、Evidence、Treatment、Linkage、mobileStage、SLA 全部内聚 | AlertService 的 mutate() 统一状态迁移与 Timeline 追加；所有跨域建单走它 | 状态/等级中文串（F-01）；assigneeId 被丢弃（F-02）；Timeline 是自由文本（F-12） |
| **AI** | **清晰，只负责检测/复核**。confirm 才建 Alert，误报不建单，AI 有独立 timeline | AiEventService.confirm :127-131；falsePositive 永不建 Alert | confirm/assign 跨聚合双写无事务（F-03）；assign 只传姓名（F-02）；阈值与 Rule 两份（F-08） |
| **Personnel** | 感知域，风险建单走 AlertService；但承载了较多告警派生字段 | intrude() :164-187 经 AlertService 建单 | status/state/risk/braceletStatus 四字段重叠；activeAlertIds/alertsToday 为派生/计数（Q3）；围栏 version 被写进 ruleVersion（F-07） |
| **Fence** | 配置/边界资产，独立版本状态机 | FenceService 独立 CRUD + publish + edge sync 模拟 | Fence 与 Rule 版本血缘未定义（F-07）；`teams` 为自由文本（FENCE-003 含未确认"检修班"）；几何为百分比坐标 |
| **Collision** | 感知/判定域，PairState 是纯运行态，Linkage 权威在 Alert | CollisionService 全部经 AlertService（:168/:187/:239/:276） | 阈值写死且与 RuleService.simulate 两份（F-08）；latestAlertId 是镜像缓存（Q2）；"待确认"混入 risk 字段 |
| **Rule** | 配置管理实体完整（版本/回滚/边缘对账/仿真/冲突检查），但**尚未参与运行时判定** | RuleService.simulate :315-349 自带阈值 | 配置与运行时脱节（F-08）；Rule 与 Fence 是否合并为配置包（Q5，建议现在不合并） |
| **Edge/Operations** | 云边同进程**模拟**（代码与文档均显式标注 SIMULATED）；离线不入 Alert、补传走 AlertService | EdgeAutonomyController 类注释；EdgeReplayService | Service 直接依赖 InMemory 实现类（F-05）；去重集合 JVM-local（F-06）；RecoveryPhase 为流程态（Q6） |

**是否存在第二套 Alert 系统：否。** Mobile 只有 accept/arrive 两个动作且委托 AlertService；Collision 的 6 步 steps 明确只是回显缓存，联动权威在 Alert.linkage；Edge 不建 EdgeAlert。

---

## 5. Data Ownership（数据权威表）

| 数据 | 当前权威来源 | 消费者 | 是否重复 | 持久化评价 |
|---|---|---|---|---|
| User（USR-*，7 人） | DemoMasterData（support） | MetaController 字典、AuthController /me、前端 dictionary/auth store | 已唯一 | sys_user |
| Team（6 个） | DemoMasterData | 字典、RiskClassifier.teamOf（按编号/姓名**推断**，Demo） | 推断逻辑是重复口径 | sys_team；正式需在事件上冗余 team |
| Area（12 个） | DemoMasterData | 字典、各 Seed、筛选 | 已唯一（主数据阶段已清洗） | safety_area；unresolved 项保持独立 |
| Alert | AlertRepository + AlertService | 全部三端 + Analytics/Overview/Screen/Mobile | 唯一 | safety_alert 聚合簇 |
| AIEvent | AiEventRepository + AiEventService | AI 页、Overview | 唯一 | ai_event |
| Personnel（P-*） | PersonnelRepository | Personnel 页、Overview 地图 | 唯一 | safety_personnel（Track 归时序边界） |
| Fence | FenceRepository | Fence 页、Personnel PIP（只读） | 唯一 | safety_fence + version |
| CollisionDevice | CollisionRepository | Collision 页、Overview | 唯一 | collision_device；PairState 不持久化 |
| Rule | RuleRepository | Rule 页、EdgeOps 对账（只读） | 唯一 | safety_rule + version |
| EdgeNode | EdgeNodeRepository | Operations/Edge | 唯一 | edge_node |
| OpsEventLog | OpsEventLogRepository | Operations 事件流 | 唯一 | ops_event_log |
| EdgePendingEvent | EdgeEventQueueRepository | Edge 补传 | 唯一 | edge_pending_event |
| Camera（8 路） | **AiDemoSeeder 内的 CameraInfo 种子**（非 DemoMasterData） | AI 页/cameras | 与设备台账口径分离 | device_camera（主数据化时收口） |
| 实时事件 | Service → Notifier/Publisher → WebSocketSessionRegistry | 前端 live store | 唯一通道 | 不落业务库；未来 Outbox |
| 设备分类/接口/部分运维事件（前端） | **前端 mock/opsData.ts 构建器** | OperationsView 设备健康面板/接口监控 | 与后端 /ops/devices 并存（F-13） | 后端 OpsInventory 已有台账，应收口 |

---

## 6. State Machine Review（状态机清单）

| Domain | 状态载体 | 当前取值（代码事实） | 代码定义位置 | DB 规划 | 问题 / 分类 |
|---|---|---|---|---|---|
| Alert 主状态 | DemoAlert.status | 待确认/已确认/待派单/待处理/处理中/待复核/已关闭/已升级 | AlertStatuses 常量类（英文常量名持中文值） | code 列 + 中文映射 | **中文值做业务键**，迁移前需 code 化（F-01） |
| Alert mobileStage | DemoAlert.mobileStage | PENDING/ACCEPTED/ARRIVED/PROCESSING（已是英文 code） | MobileStages | 子状态列 | Demo 过渡工作流子状态（Q1） |
| Alert Linkage step | LinkageStep.state | done/active/failed/idle（英文） | AlertDemoSeeder 模板、AlertService | linkage_step 表 | 健康 |
| AI 复核 | DemoAiEvent.status | 待复核/已确认违规/误报/不确定/已派单/处理中/已关闭（中文） | AiReviewStatuses 常量类 | code 列 | 同 F-01 |
| AI 风险 | DemoAiEvent.risk | 高/中/低（中文，与 Alert 四级不同词表） | AiEventService.mapAlertRisk 集中映射 | 展示+映射 | 词表不同是**刻意设计**（感知置信≠处置等级），保留映射 |
| Personnel 在线 | status | 在线/离线（中文字面量比较） | PersonnelService :74 等 | 状态列 | F-01 |
| Personnel 风险 | risk | 正常/关注/高风险（中文） | PersonnelService :68-76 | 状态列 | F-01；与 Alert 四级不强行统一 |
| Personnel 态势 | state | normal/warning/danger/offline（英文） | DemoPersonnel | 派生 | 与 status/risk 重叠，正式改多值标签 |
| 手环 | braceletStatus | 在线/离线/低电量（中文） | PersonnelService | 设备态 | F-01 |
| Fence | DemoFence.status | 草稿/待评审/待发布/已生效/已停用/版本不一致（中文常量类） | FenceStatuses | code 列 | F-01 |
| Rule | DemoRule.status | 草稿/待评审/已批准/发布中/已生效/版本异常/已停用（中文常量类） | RuleStatuses | code 列 | F-01 |
| Rule 边缘同步 | DemoRule.edgeNodes[].state | synced/syncing/mismatch（英文） | RuleService | rule_edge_sync | 健康 |
| Collision 风险 | DemoCollisionDevice.risk / PairState.risk | 安全/预警/严重/紧急/**待确认**（中文） | CollisionService :164/:308 | 设备健康+风险两列 | "待确认"是雷达断数健康态，应与 RiskLevel 拆分 |
| Edge 节点 | DemoEdgeNode.status | ONLINE/DEGRADED/OFFLINE/RECOVERING/ERROR（英文） | EdgeNodeStatuses | code 列 | 健康 |
| PendingEvent | EdgePendingEvent.status | PENDING/SYNCING/SYNCED/FAILED/DUPLICATE（英文） | PendingEventStatuses | code 列 | 健康 |
| RecoveryPhase | RecoveryPhase | CONNECTIVITY/CLOCK_RECONCILIATION/RULE_RECONCILIATION/EVENT_REPLAY/FINAL_CHECK（英文） | RecoveryPhases | **不建表**（Q6） | 运行流程态 |
| Alert 风险等级 | DemoAlert.risk | 一般/预警/严重/紧急（**中文字面量散落 ~20 处，无常量类**） | AlertService:88/90/196/353/445、RiskClassifier:24、Overview/Screen/Mobile/Analytics/Collision/EdgeOps 等 | code 列+约束+索引 | **F-01 最核心项**：状态有常量类、等级没有 |

**关键判断**：状态机本身（迁移规则、ACTIVE 集合、409 守卫）设计是健全的，可以保留；问题不在状态机逻辑，而在**持久化键使用中文展示串**。

---

## 7. Repository Review（9 个 Repository 逐项审计）

| Repository | 所属域 | 消费方（跨域标注） | 跨域写 | 接口现状问题 | 迁移风险 |
|---|---|---|---|---|---|
| AlertRepository | Alert | AlertService；Analytics/Personnel/Mobile/SafetyProjection/Overview **只读** | **Analytics.simulate-surge 直写（F-04）** | 直接返回可变 DemoAlert；timeline/evidence/linkage/treatment 为内嵌 List；`deleteById` 仅为演示恢复而存在；timeRange 过滤未实现 | **HIGH**（聚合最复杂、子实体最多） |
| AiEventRepository | AI | AiEventService；Overview 只读 | 无 | 可变对象 + 内嵌 timeline/boxes | MEDIUM |
| PersonnelRepository | Personnel | PersonnelService；Overview 只读 | 无 | 可变对象；含 base* 瞬态基线快照字段；TrackPoint 不落存储 | MEDIUM |
| FenceRepository | Fence | FenceService；Personnel/Overview 只读 | 无 | 可变对象；polygon/nodes/teams 内嵌；构造器 reset() 灌种 | MEDIUM |
| CollisionRepository | Collision | CollisionService；Overview 只读 | 无 | 设备主数据与 PairState 运行态混在一个存储模型；latestAlertId 镜像 | MEDIUM |
| RuleRepository | Rule | RuleService；EdgeOps 只读 | 无 | versions/versionSnapshots/edgeNodes 深嵌套；回滚靠内存对象复制 | MEDIUM-HIGH |
| EdgeNodeRepository | Ops | **EdgeOpsService 直接声明为 InMemoryEdgeNodeRepository 具体类型** | 无 | `findMutable()` 只存在于实现类，原地改后不 save | **HIGH（契约泄漏）** |
| EdgeEventQueueRepository | Ops | EdgeOpsService、EdgeReplayService（均强制转型 `(InMemoryEdgeEventQueueRepository)` 调 findMutable :609/:223） | 无 | 接口与实现契约不一致；`clear()`；顺序补传依赖内存排序 | **HIGH（契约泄漏）** |
| OpsEventLogRepository | Ops | EdgeOpsService、EdgeReplayService（**字段类型直接是 InMemoryOpsEventLogRepository**） | 无 | `clear()`；内存 MAX_KEEP 截断 | LOW-MEDIUM |

**共性契约问题（JDBC 实现无法直接满足，F-05）：**

1. `findById()/findAll()` 返回的是存储容器内部的**可变引用**，业务代码普遍"取出对象 → 原地改字段 → 偶尔 save"（EdgeOpsService :159/:770 的 `findMutable` 是显式化的此类用法）。关系库 + 行级映射下这一模式不成立，需要显式的命令式方法（`appendTimeline`、`updateStatus`、`markSynced`）或"取出快照 → 修改 → 整体 save"且 save 真正 upsert 子表。
2. 聚合根与子实体（timeline/evidence/treatment/linkage/versions/polygon/nodes）目前是同一对象图一次 `save`，落库时是 1 张主表 + N 张子表的级联写入，Repository 接口没有表达这层语义。
3. `reset()/clear()/deleteById()/findMutable()` 是 Demo/测试专用方法，不应进入生产 Repository 契约（建议下沉到 DemoProfile 的 SeedManager）。
4. 领域模型（DemoXxx）同时是 API DTO（Jackson 直接序列化）与存储实体。DB 阶段不要求立刻三层分离，但 JDBC Repository 内部需要 Entity ↔ Domain 的映射，避免把 `@Table` 注解污染到对外 DTO。
5. `findAll()` 被 Projection 和跨域只读大量使用做内存 stream 过滤，落库后是全表扫描，需要在迁移时引入显式查询（按状态/dedupKey/对象/时间窗）。

---

## 8. Transaction Boundaries（事务边界）

当前所有写操作在单 JVM 内靠 `synchronized`（AlertService 写方法 synchronized、AiEventService `synchronized(id.intern())` :343）顺序执行，没有回滚概念。数据库时代以下业务动作是**天然事务单元**，目前代码结构上没有编排层：

| 业务动作 | 当前涉及的写 | 风险 | 建议 |
|---|---|---|---|
| AI confirm | 写 AiEventRepository（状态/复核人/timeline）+ AlertRepository（createFromAi 建单+timeline+广播）+ 回写 linkedAlertId | 两步之间失败会产生"AI 已确认但无 Alert"或反之；且当前会先后触发 alert.new 与 ai.reviewed 两类事件 | 引入应用编排（use-case service），单事务；事件 AFTER_COMMIT 发布 |
| AI assign | 上者 + alertService.confirm + alertService.assign + AI 事件状态/assignee 更新（AiEventService :175-196） | 一个动作跨 4~5 次写 | 同上 |
| Collision 严重→紧急升级 | PairState 内存态 + AlertService.upgradeRisk（写 Alert + timeline + 广播） | PairState 不持久化，失败可重放；但顺序需定义 | Alert 写在事务内，PairState 为可重建派生态 |
| Personnel intrude | 人员位置/风险字段 + createRiskAlert | 人员态与告警可能短期不一致 | 单事务；人员实时态也可接受最终一致（文档注明） |
| Edge replay（逐条） | EdgePendingEvent 状态翻转 + OpsEventLog + createEdgeReplayAlert + 回写 linkedAlertId | 补传中途失败的重试/重复语义 | 单事务 + 幂等键约束；顺序消费 |
| Rule publish | Rule 状态/版本 + 4 个 edgeNodes 同步态 + 版本快照 | 边缘同步是模拟的，真实场景是异步回执 | 中心侧 Rule+Version 同事务；edge_sync 状态随回执独立事务更新 |
| Fence publish | Fence 状态/版本 + polygon 快照 + edge 同步态 | 同上 | 同上 |
| Alert treatment/review | Alert 状态 + treatment 记录 + timeline | 同一聚合多表 | 单事务（聚合内，最简单） |

**结论**：不需要现在就上重型编排框架，但需要在 Repository 迁移前确定两类边界——①聚合内多表写入（Repository 实现内部事务）；②跨 AI↔Alert、Edge↔Alert 的编排事务（薄应用服务 + Spring `@Transactional`，事件发布改 AFTER_COMMIT 或 Outbox）。这是 F-03。

---

## 9. Realtime Event Review（实时事件体系）

**事件结构统一**：`LiveEvent(type, eventId=UUID, ts, traceId, data)`，21 个业务 type + ping/pong；全部走 `domain.action` 小写命名；data 只放 id/状态摘要，明确禁止完整实体/Timeline/Evidence/截图（LiveEventTypes.java、LiveEvent.java 注释）。

| type | 发布者（代码） | 触发 | 载荷 | 未来 MQ |
|---|---|---|---|---|
| alert.new | WebSocketAlertChangeNotifier（AlertService create*）；**另被 AnalyticsService.injectSurge :328 直接发布（F-04）** | 风险建单/补传/演示突增 | alertId/status/level | 直接可搬 |
| alert.changed / assigned / treatment / closed / escalated / linkage.changed | AlertService → Notifier | 各处置动作 | 轻量摘要 | 直接可搬 |
| ai.new / ai.changed / ai.reviewed | AiEventService → WebSocketAiChangeNotifier | 模拟/复核/派单/处理/关闭 | aiEventId/status/confidence/linkedAlertId | 直接可搬 |
| person.moved | PersonnelService → DomainLivePublisher | 位置/异常模拟 | id+态势摘要 | 直接可搬 |
| fence.changed | FenceService → DomainLivePublisher | 编辑/发布/异常/重下发/停用 | id+version+status | 直接可搬 |
| collision.changed / risk.changed / linkage.changed | CollisionService → DomainLivePublisher | 接近/风险/联动 | id+distance+risk | 直接可搬 |
| rule.changed / published / sync.changed | RuleService → DomainLivePublisher | 生命周期/发布/对账 | ruleId/version/synced | 直接可搬 |
| ops.node.changed / sync.changed / queue.changed / recovery.changed | EdgeOps/EdgeReplay → OpsLiveNotifier | 断网/对账/队列/恢复 | nodeId/status/phase 等 | 直接可搬 |
| system.notice | **常量已定义，当前无发布者** | — | — | 预留 |
| ping/pong | LiveWebSocketHandler :44-48 直接回复 | 客户端 20s 心跳 | ts | 不进 MQ |

**评价：**

- **发布时机正确**：全部在业务写成功之后由 Service 调用，Controller 零广播（已扫描确认）；广播 best-effort，异常被吞，不回滚业务——符合"通知不是事实源"。
- **无 Service 直接依赖 WebSocketSessionRegistry**：只有 DomainLivePublisher 和两个 WebSocket*Notifier 实现类接触它。RocketMQ 迁移方式明确：替换这 3 个实现/加 Outbox，业务 Service 零改动。**可迁移性高**。
- **多实例缺口（F-09，MEDIUM/BEFORE_SERVER_DEPLOY）**：SessionRegistry 是单实例 JVM-local；DomainLivePublisher 直接广播本机会话。多实例需要 Outbox/MQ 扇出或 sticky session。
- **traceId 缺失（LOW）**：DomainLivePublisher 与两个 Notifier 调 `LiveEvent.of(type, ts, null, data)`，traceId 恒为 null，事件与 HTTP 请求链路无法关联。
- **AI confirm 双事件是刻意的**：alert.new 与 ai.reviewed 各通知各域订阅者，不算重复广播；但数据库时代两类事件必须在事务提交后才能发，否则订阅者 REST 重拉会读到未提交数据（F-03 的一部分）。
- **前端订阅口径缺口（LOW）**：前端 `isLiveEvent()`（api/live.ts :116-123）识别 alert/ai/system/person/fence/collision/rule 前缀，**不含 `ops.`**；运维页的实时刷新实际依赖用户动作后的 REST 重拉，ops.* 四类事件当前没有前端消费者。后端已发、前端未接，后续接入即可。

---

## 10. Projection Review（投影边界）

- **全部无状态、无 Repository、无写回**：ScreenService 委托 SafetyProjectionService；MobileService 只读 AlertRepository；AnalyticsService 只读 AlertRepository 聚合（唯一写是演示开关 F-04）；OverviewService 只读 5 个 Repository + 经 AlertService.demoRisk 走演示开关（:127-128，与 Analytics 的直写形成正反对照——Overview 的做法是正确示范）。
- **重复口径（F-10，LOW/MEDIUM）**：高风险判定字面量在 RiskClassifier.isHighRisk（:24）、AlertService（:196/353/445）、OverviewService（:88/97/153/175）、ScreenService（:63）、MobileService（:37）、AnalyticsService（:99-100）重复出现；首页 riskDistribution 按细分 eventType、Analytics 按 RiskClassifier.analyticsType 归并大类，两套口径刻意保留（issues 文档已记录）。建议落库前把等级判定与事件分类收口为共享查询/分类组件，但不阻塞。
- **Analytics 演示基线**：趋势历史桶叠加确定性 DEMO baseline、当天只算真实数据，逐点 `demoBaseline` 标记；KPI/分布/排行不叠加。属显式标注的演示数据，正式由 openGemini/事件库提供历史后移除。
- **Analytics 不建表的结论成立**：dataset/KPI/趋势/排行全部可由 safety_alert（+ ai_event）SQL 聚合得到。

---

## 11. Frontend State Review（前端数据权威）

| Store | 分类 | 评价 |
|---|---|---|
| auth | Global Infrastructure | 拉 /auth/me，Header 唯一用户来源；无硬编码用户 |
| live | Global Infrastructure | 单条 /ws/live，connecting/connected/reconnecting/disconnected；自动重连 |
| dictionary | Global Infrastructure | App 启动 ensureLoaded 一次，inflight Promise 去重，内存缓存，失败显式 error，无 localStorage |
| incident | Projection Cache（大屏+移动） | 只读加载/写操作转发 alertApi/mobileApi，WS 触发 reload；注释明确不再用 BroadcastChannel |
| operations | UI 模拟态 | 仅云边链路 linkState（断网自治演示横幅），不持有后端事实 |

- **无两个 Store 维护同一后端事实**；adapter 全部是纯映射（22 个 adapter，均有 spec），未发现 adapter 推进状态机/做风险判定/改主数据。
- **无 localStorage / sessionStorage / BroadcastChannel 运行时使用**（全量扫描，仅 incident.ts 注释中提及历史）。
- **API 层无静默 Mock 切换**：http.ts 失败统一抛 ApiError；AlertCenter 的 mock 回退被 `VITE_ENABLE_ALERT_MOCK_FALLBACK` 显式开关保护，默认关闭（AlertCenterView :26/:86）。
- **派单双弹窗**：AlertAssignDialog 与 ai/AssignmentDialog 都从 dictionary store 取 7 人，无本地人员常量，失败显式错误+重试。
- **F-13（MEDIUM）**：OperationsView 的设备分类/接口/运维事件流在页面初始化时直接取 `mock/opsData.ts` 的 `buildDeviceCategories()/buildInterfaces()/buildOpsEvents()`（:32-34），**之后不被任何后端响应替换**；后端已有 GET /ops/devices、/ops/interfaces、/ops/events 与 OpsInventory 台账。这是当前默认运行模式下仍"偷偷参与正式运行"的 D 类 mock（未加开关、未标 demo），是前端最主要的数据权威缺口。DeviceHealthPanel/SystemTopology 仅用其中纯函数 `onlineCount`，无问题。
- **F-14（LOW）**：mock/aiEvents.ts、incidents.ts、analyticsData.ts、ruleData.ts 经全量扫描**已无任何 import 引用**（含 spec），属退出运行链的死 fixture，可在后续清理。
- 已知不处理项：echarts 主 chunk >500kB（本阶段明确不做分包）。

---

## 12. Demo / Mock / Seed / Simulator 边界清单

| 类别 | 具体内容 | 当前启用条件 | 生产前处理 | 级别/时机 |
|---|---|---|---|---|
| Seeder | AlertDemoSeeder、AiDemoSeeder（@Order 11 CommandLineRunner）、RuleDemoSeeder；Fence/Collision/Personnel/EdgeNode 种子在各 InMemory 仓库构造器 reset() 中；OpsInventory @Component 台账 | **无条件，所有 profile（含 server）启动即执行**（全库仅 RocketMqAdapter 一处 @ConditionalOnProperty，无任何 @Profile） | 拆 dev/demo 与 prod：种子仅 demo profile；生产不得灌演示数据 | **BLOCKER / BEFORE_SERVER_DEPLOY（F-11）** |
| 模拟端点 | /ai-events/simulate、/analytics/simulate-surge、/collision/simulate、/fences/{id}/simulate-mismatch、/edge/simulate-link、/ops/simulate、/personnel/{id}/simulate-abnormal、/overview/simulate-risk、/rules/{id}/simulate-mismatch、/rules/simulate（10+ 个 POST） | **无 profile/权限/注解限制，server profile 同样暴露**；surge 会真实写入/删除 ALM-SURGE- 告警 | demo profile 限定或加权限/禁用；生产关闭 | **BLOCKER / BEFORE_SERVER_DEPLOY（F-11）** |
| 演示开关 | AlertService.demoRisk（ALM-DEMO-RISK 固定告警，经正规主链）、simulate-surge（直写仓储 F-04） | 同上 | 移出生产包/禁用 | F-04/F-11 |
| 前端 mock 回退 | AlertCenter ALERT_METRIC_BASE/createInitialAlerts | env 显式开关，默认关 | 保留为开发兜底或移除 | 可接受 |
| 前端 D 类 mock | OperationsView 设备分类/接口/事件（opsData 构建器） | 默认运行即生效 | 接 /ops/* 真实接口或显式标 demo | MEDIUM（F-13） |
| 死 fixture | mock/aiEvents、incidents、analyticsData、ruleData | 无引用 | 删除 | LOW（F-14） |
| SIMULATED 数据 | 坐标百分比、pos/distanceM 稳定哈希、照片 dataURL、PLC/雷达/AI 推理、边缘自治、时钟偏差、指标确定性样本、趋势基线 | Demo 固有 | 真实接入时替换 Repository/模拟器，契约不变 | Accepted Debt |
| Demo 用户 | DemoUserProperties（/auth/me 默认 USR-001，app.demo.user.* 可覆盖）；写操作缺省操作人取该用户 | 配置化 | 接 IAM/SSO，操作人改安全上下文 | Accepted Debt（F-15） |

---

## 13. Database Design Alignment（与数据库蓝图的反向核对）

对 `docs/database-design/`（29 张活动表 DDL + safety_personnel_track 注释模板）逐表反查代码后的总体判断与补充 findings 详见 **`docs/database-design/database-architecture-review-findings.md`**。要点：

- 未发现把 Projection/DTO 误建成表；29 张表均能在代码中找到需要长期持久化的事实对应。
- 未发现 unresolved 区域被外键提前合并；USR-* 与 P-* 分表正确。
- 需要设计修订（**只记录，不改**）的重点：风险/状态 code 化、assignee_user_id 当前无值可写、Fence version 错记为 ruleVersion、upgradedFrom 语义是"上一等级文案"而非外键、latestAlertId/activeAlertIds/alertsToday/slaRemainingSec 为派生值不应落主表、mobileStage 作为子状态列、RecoveryPhase 不建表、Ops 三表的 InMemory 契约泄漏需先修。
- 表级 KEEP/RECONSIDER/DEFER 建议（如 sys_dictionary、sys_idempotency_record、outbox_event、safety_attachment、rule_area、edge_sync、时序三表）见 findings 文件。

---

## 14. Multi-instance Readiness（多实例准备度）

当前 JVM-local、多实例下会出问题的能力：

| 能力 | 当前实现 | 多实例风险 | 级别/时机 |
|---|---|---|---|
| WebSocket 会话 | WebSocketSessionRegistry 内存表 | 事件只广播到本实例连接 | HIGH / BEFORE_SERVER_DEPLOY（F-09） |
| Alert 编号 | AlertService.nextAlertId() 按日内存计数（ALM-yyyyMMdd-NNN） | 两实例同日编号冲突 | HIGH / BEFORE_SERVER_DEPLOY（F-16）；DDL 已规划 UUID 主键+业务编号 UQ，需配号段/序列/UUIDv7 |
| InMemory 仓储 | ConcurrentHashMap/CopyOnWriteArrayList | 状态不共享（落库即解决） | 落库主线 |
| Edge 补传去重 | EdgeReplayService 的 processedEventIds/processedIdemKeys 两个 HashSet（:56-57） | 重启丢失、实例间不共享 | HIGH（随 edge_pending_event 唯一约束 + 幂等存储解决） |
| 幂等 | StringRedisTemplate→Kvrocks；Kvrocks 不可用**降级直放**；仅 Alert/AI 两个 Service 接入 | 无 Kvrocks 时多实例重复写；Rule/Fence/Personnel/Collision/Ops 写操作未接服务端幂等 | MEDIUM（F-17） |
| 定时任务 | **全库无 @Scheduled/ScheduledExecutor/new Thread（已扫描确认）** | 无重复调度风险 | 健康 |
| 指标采样 | 按请求确定性生成，无后台采样线程 | 无多实例问题；正式采样应写 openGemini | Accepted Debt |
| 演示数据/锁 | synchronized / id.intern() | 仅单 JVM 有效；落库后靠唯一约束+事务+乐观锁 | 随迁移解决 |

---

## 15. Infrastructure Boundaries（基础设施职责核对）

代码现状与既定职责边界**一致，未发现越界倾向**：

- **openGauss**：pom 已含 opengauss-jdbc + spring-boot-starter-jdbc，application.yml 已配 HikariCP（驱动 org.postgresql.Driver、jdbc:postgresql://、默认库 b_project），dev profile `initialization-fail-timeout: -1` 允许无库启动；当前**没有任何业务代码使用 DataSource/JdbcTemplate**（仅探针），无 JPA/MyBatis/Flyway/Liquibase。
- **Kvrocks**：Lettuce，仅 IdempotencyService 使用（TTL 600s，PROCESSING 占位，故障降级放行）；未被用作业务事实源。
- **RocketMQ**：RocketMqAdapter 存在但 `app.rocketmq.enabled=false`（server profile 默认 true），当前无业务生产者/消费者。
- **openGemini**：OpenGeminiClient 仅 /ping 探针，enabled=false；无任何时序写入。
- **文件/附件**：treatment.attachment 只存"现场照片 N 张（Mock）"计数文本，无 BLOB/Base64 入库；AI 截图为前端示意。蓝图 safety_attachment 只留 storage_key/url 引用，方向正确。
- **密钥**：RTSP/MQ/DB 密码均走环境变量/配置，代码与模型中无明文凭据。
- **时间模型（F-18，MEDIUM）**：核心事件时间已统一 OffsetDateTime + 固定 Clock（Asia/Shanghai 注入）；但仍混用展示用 String 字段——DemoAlert.time（HH:mm:ss）、DemoAiEvent.time、DemoCollisionDevice.lastUpdated、DemoPersonnel.lastUpdated、DemoFence.effectiveAt/expiresAt、DemoRule.updatedAt/effectiveAt、Version.date；RuleService 唯一一处 LocalDateTime 仅用于格式化。落库前需明确：timestamp with time zone 为权威、展示串由前端格式化，避免把 String 时间搬进 timestamptz 列。
- **时区**：服务端 Jackson time-zone Asia/Shanghai、WS ts 用 ZONE 生成；Edge 有 edgeOccurredAt/serverReceivedAt 双时间且不回改（审计语义正确）。浏览器/Edge 本地时区与服务器的一致性在真实边缘阶段验证。

---

## 16. Findings（问题总清单）

### BLOCKER（落库/上服务器前必须解决）

| ID | 问题 | 代码位置 | 影响 | 修复时机 |
|---|---|---|---|---|
| F-01 | **风险等级无 code 常量、中文串直接做业务判断**（状态虽也是中文但已有 *Statuses 常量类收口；风险等级没有）。约 20 处：AlertService:88/90/196/353/445，RiskClassifier:24，Analytics:99-100，Overview:88/97/153/175，Screen:63，Mobile:37，Collision:257，EdgeOps:555，Personnel:68-76/110/181，RuleService:358，AiEventService:96/173/397-398 等 | 见左 | 若按现状迁移，中文展示文案会成为 safety_alert.risk 等列的存储值、查询条件、check 约束与索引键，后续改码成本极高 | **NOW / BEFORE_REPOSITORY_MIGRATION**：先建 RiskLevels/各域状态 enum 或常量（机器 code + 中文展示映射），DDL 按 code 落列 |
| F-11 | **Seeder 与 10+ simulate 端点无 profile/权限隔离**，server profile 启动同样灌种、同样可被调用并真实写删演示告警 | 3 Seeder（@Component CommandLineRunner，无 @Profile）；各 Controller simulate* 映射；InMemory 仓库构造器灌种 | 生产环境数据被污染、演示接口可被任意调用 | **BEFORE_SERVER_DEPLOY（上服务器前 BLOCKER）**；不阻塞本地落库 |

### HIGH（Repository 迁移前必须解决）

| ID | 问题 | 代码位置 | 修复时机 |
|---|---|---|---|
| F-02 | **Alert assign 收到 assigneeId/assigneeName 但完全丢弃，只按姓名落 assignee**；AI assign 同样把姓名塞进 assigneeId 位（AiEventService:184）。蓝图 safety_alert.assignee_user_id 将无值可写，人员改名即断链 | AlertService.assign:107-130（无 req.assigneeId 读取）；AiEventService:167-196 | BEFORE_REPOSITORY_MIGRATION（改 Service 落 ID + 姓名快照，API 契约保持兼容） |
| F-03 | **跨聚合写无事务/编排边界**：AI confirm/assign、Edge replay 一个动作写两个 Repository 并发两类事件，无 AFTER_COMMIT 概念；未来会"先广播后提交/半成功" | AiEventService:127-131/175-210；EdgeReplayService；CollisionService:168 | BEFORE_REPOSITORY_MIGRATION（薄编排服务 + @Transactional + 事件 AFTER_COMMIT/Outbox） |
| F-05 | **Repository 契约泄漏 InMemory 形态**：EdgeOpsService 字段直接声明 InMemoryEdgeNodeRepository/InMemoryOpsEventLogRepository；EdgeOps:609 与 EdgeReplay:223 强制转型 InMemoryEdgeEventQueueRepository 调 findMutable；findById/findAll 返回内部可变对象；内嵌子实体 List 随 save 全量覆盖；clear()/deleteById()/reset() 为 Demo 方法 | EdgeOpsService:17-19/54-67/159/609/770；EdgeReplayService:50-66；9 个 Repository 接口 | BEFORE_REPOSITORY_MIGRATION（接口去 Demo 方法、补显式命令方法、聚合级持久化语义） |
| F-07 | **Fence 版本被错记为规则版本**：人员越界建 Alert 时 ruleId 固定占位 RULE-PER-001、ruleVersion 写 fence.version（PersonnelService:181-185）；Fence.teams 为自由文本（FENCE-003 含未确认"检修班" InMemoryFenceRepository:42）。蓝图 rule_id/rule_version 列会记录语义错误的值 | 见左 | BEFORE_REPOSITORY_MIGRATION（明确 rule/fence 两条版本血缘，Alert 证据来源分别记录） |
| F-16 | Alert 业务编号为单实例内存按日计数，多实例冲突；EdgeReplay 去重集合 JVM-local | AlertService.nextAlertId:552；EdgeReplayService:56-57 | 随首批落库引入序列/号段/UUIDv7 + 唯一约束（DDL 已预留） |

### MEDIUM（多实例/服务器部署前解决）

| ID | 问题 | 位置 | 时机 |
|---|---|---|---|
| F-04 | AnalyticsService.injectSurge 跨域**直写 AlertRepository.save/deleteById 并自发 ALERT_NEW**，绕过 AlertService 状态机/幂等/统一广播；对照 OverviewService.simulateRisk 正确委托 alertService.demoRisk | AnalyticsService:60/286-330；AlertRepository.deleteById 注释 | BEFORE_SERVER_DEPLOY：改为经 AlertService 的演示方法，或随 F-11 仅 demo profile 存在 |
| F-06 | Edge 补传幂等键为 Demo 口径 `node:eventType:epoch:seq`，不校验同键不同负载；processed*Set 重启即失 | EdgeReplayService:56-57；EdgePendingEvent | 随 edge_pending_event 落库解决 |
| F-08 | 碰撞/AI 阈值在运行时 Service 与 RuleService.simulate 各写一份，运行时判定不读 RuleRepository，改规则不影响真实风险 | CollisionService:164/308；RuleService:315-349 | LATER（规则引擎阶段；不阻塞落库，Rule 表先如实存配置） |
| F-09 | WS SessionRegistry、DomainLivePublisher 单实例广播 | common/realtime | BEFORE_SERVER_DEPLOY（Outbox→MQ fan-out） |
| F-12 | 响应/到场/关闭时长靠 Timeline 文本关键字匹配（"确认/接单/关闭"），非结构化时间戳；TimelineEvent 本身只有 time/text/state | RiskClassifier/AnalyticsService；TimelineEvent record | BEFORE_REPOSITORY_MIGRATION（timeline 表加 event_type/sequence，时长从结构化字段算） |
| F-13 | 前端 OperationsView 设备分类/接口/事件流默认使用 mock/opsData 构建器且不被后端数据替换 | OperationsView:32-34 等 | BEFORE_SERVER_DEPLOY（接 /ops/* 或显式标 demo） |
| F-17 | 服务端幂等仅 Alert/AI 两域接入；Rule/Fence/Personnel/Collision/Ops 写接口未走 IdempotencyService；Kvrocks 故障降级直放 | IdempotencyService 消费方扫描 | BEFORE_SERVER_DEPLOY |
| F-18 | 多个 lastUpdated/effectiveAt/expiresAt/time 为 String，与 OffsetDateTime 混用 | 见第 15 节 | BEFORE_REPOSITORY_MIGRATION（统一 timestamptz 权威） |
| F-19 | Personnel 四字段（status/state/risk/braceletStatus）语义重叠；Collision risk 混入"待确认"健康态 | DemoPersonnel；CollisionService | LATER（感知态/健康态/风险等级拆分，可在迁移时顺手按列分开） |

### LOW（产品化/性能阶段）

| ID | 问题 | 位置 |
|---|---|---|
| F-10 | 高风险判定/事件分类口径在多个 Projection 重复字面量；首页与统计页风险分布两套口径（刻意保留） | Overview/Screen/Analytics/RiskClassifier |
| F-14 | mock/aiEvents、incidents、analyticsData、ruleData 已无任何引用（死 fixture） | frontend/src/mock |
| F-15 | 操作人/当前用户依赖 DemoUserProperties，无认证授权边界；review 固定演示人 | support/demo；各 Service pick() |
| F-20 | LiveEvent.traceId 恒 null；ops.* 事件前端无消费者 | DomainLivePublisher/Notifier；api/live.ts |
| F-21 | AlertQuery.timeRange 后端未实现过滤（只支持 from/to） | InMemoryAlertRepository |
| F-22 | RiskClassifier.teamOf 按编号段/姓名推断班组；重复风险按 target 正则提编号 | RiskClassifier |
| F-23 | AlertController 签名暴露 Repository 嵌套类型 AlertPageResult | AlertController:52 |
| F-24 | "已升级"状态无回到处理链的标准路径（escalate 后与处置状态共存规则未定义） | AlertService:248-260 |
| F-25 | 百分比画布坐标 vs 正式 GIS；relSpeed/制动距离/天气未参与碰撞判定 | Polygon2D；CollisionService |

---

## 17. Recommended Fix Order（建议修复顺序，仅建议，不执行）

```text
0. 架构评审人工确认（本报告 + database-architecture-review-findings）
   ↓
1. NOW：风险等级/各域状态 code 化（F-01），先于任何 Repository JDBC 实现
   同时定案：assignee 落 ID+快照（F-02）、Fence/Rule 版本血缘（F-07）、时间类型收口（F-18）
   ↓
2. 数据库蓝图按 findings 做一轮人工修订（只改文档/DDL 草案，不建库）
   ：派生字段剔除、mobileStage 子状态列、timeline event_type/sequence、表优先级调整
   ↓
3. Repository 契约兼容性改造（F-05）：去 Demo 方法、显式命令、聚合持久化语义、
   消除 InMemory 具体类型依赖；InMemory 实现继续全绿
   ↓
4. Compatibility Spike：选 1 张主表（建议 sys_team/safety_area 主数据）走通
   openGauss JDBC（不引 ORM 的前提下验证 JdbcTemplate/行映射/事务/Profile 切换）
   ↓
5. Master Data 持久化（sys_user/sys_team/safety_area）+ Seeder profile 隔离（F-11 的一半）
   ↓
6. Alert 聚合簇迁移（含 F-02/F-03/F-12/F-16；事务编排 + AFTER_COMMIT 事件）
   ↓
7. AI / Personnel / Fence / Collision / Rule / Ops 依次迁移
   Analytics/Overview/Screen/Mobile 保持 Projection 不动，只换查询来源
   ↓
8. 服务器部署前：F-04/F-09/F-11(模拟端点)/F-13/F-17、编号多实例、MQ fan-out、IAM 接入
   ↓
9. LATER：规则运行时化（F-08）、GIS、对象存储、openGemini 高频时序、状态机细化（F-24）
```

---

## 附：十个重点架构问题的结论（Q1–Q10）

**Q1：mobileStage 应不应该成为正式 Alert 持久化字段？**
属于 **C（工作流子状态）+ Demo 过渡**，不是主状态机的一员。建议：Phase 1 在 safety_alert 以独立列 `mobile_stage` + `accepted_at/arrived_at` 持久化（值已是英文 code，成本低），主 status 保持现有 8 态；若未来移动端处置流程继续长大（转单、催办、多班组到场），再独立为"处置工单/任务"实体。不要把接单/到场并入主状态枚举。

**Q2：latestAlertId 是否应该成为 CollisionDevice 持久字段？**
**不应该。** 它是 PairState.activeAlertId 镜像到设备上的运行态缓存（CollisionService:109-111/198），可由"该设备对、未关闭、dedupKey=COLLISION:a:b"的告警查询派生。collision_device 不存该列；配对瞬时态留内存/Kvrocks，关键事件已在 Alert。

**Q3：activeAlertIds 是否应该成为 Personnel 持久字段？**
**不应该。** PersonnelService 已用 `alertRepository.findAll()` 实时派生（:86），intrude() 里 `activeAlertIds = List.of(alert.id)`（:186）是演示镜像；alertsToday 同理是可从告警聚合的计数。safety_personnel 只存人员当前态势，活跃告警关系由 safety_alert 反查，避免双写不一致。

**Q4：AIEvent 和 Alert 是否需要双向 linked 字段？**
保留**单向为主、双向软引用**：AI 侧 `linked_alert_id` 为主要关联（AI 负责建单后回写），Alert 侧可留 `linked_ai_event_id` 作溯源软引用（无 FK 约束、可空）。**不要做双向状态同步**（AI close 不联动 Alert close 是正确的）。双写一致性靠 F-03 的单事务保证：AlertService 建单返回 id → 同事务内 AI 回写。

**Q5：Rule 与 Fence EdgeSync 是否应该保持独立？**
**现在保持独立。** 二者变更频率、下发粒度、回滚语义、payload 大小都不同（判定策略 vs 地理边界），DDL 中 rule_edge_sync / fence_edge_sync 分表正确。"边缘配置包统一版本指纹"是真实边缘 Agent 阶段的演进点，过早合并会让 Demo 背负错误抽象；可在 edge_node 上预留 config_bundle_version 概念位但不建表。

**Q6：RecoveryPhase 是否需要表？**
**不需要。** 它是一次恢复流程的运行时阶段展示（5 阶段），每次 recover 重新生成、结束即终态；ops_event_log 已逐阶段记录权威审计。需要的话最新流程态可放 Kvrocks/内存，不进 openGauss。

**Q7：Alert Linkage 是否确实是现场联动唯一权威记录？**
是，且应继续如此。Collision PairState.steps（6 步）只是页面回显缓存，所有联动/接管/失败的权威写入都经 AlertService.linkage/takeover（CollisionService:239/267/276）。safety_alert_linkage + linkage_step 的设计成立；设备侧未来只持有"当前活跃联动指令引用"。

**Q8：Analytics 是否完全可以保持 Projection？**
是。dataset/KPI/趋势/风险类型/Area Ranking/Team Efficiency/Device/Person 排行全部可从 safety_alert（+ai_event）SQL 聚合，无任何需要独立存储的事实（simulate-surge 是演示写开关，不是统计存储）。不建 analytics_* 表；未来数据量大再评估物化视图。

**Q9：Overview / Screen 是否存在重复 Projection 逻辑？**
部分存在、已部分收敛：Screen 已委托 SafetyProjectionService 与 Overview 共用计数口径；但高风险字面量判定散落 4+ 处（F-10），首页 riskDistribution（细分 eventType）与 Analytics（analyticsType 归并）仍是两套口径（刻意保留并有测试锁定）。建议落库前把风险判定/分类抽为共享查询组件，口径是否统一由产品决定，不阻塞迁移。

**Q10：当前 Repository 接口是否适合直接增加 JDBC 实现？**
**不适合直接实现，需要先做契约修订（F-05）**：①返回内部可变对象 + 原地修改模式不成立；②聚合内嵌 List（timeline/evidence/treatment/versions/polygon/nodes）需要级联子表语义；③findMutable/clear/deleteById/reset 等 Demo 方法要移出生产契约；④Ops 两个 Service 对 InMemory 实现类的直接依赖与强制转型必须消除；⑤findAll 全表扫的 Projection/跨域读取要改为显式查询。修订后 InMemory 与 Jdbc 两套实现可以并存、同测，迁移风险可控。
