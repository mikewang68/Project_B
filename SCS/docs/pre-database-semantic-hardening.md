# Database Pre-Migration Phase A：落库前业务语义与运行边界修复

> 阶段：Database Pre-Migration Phase A（只修语义，不落库）
> 日期：2026-09-20
> 范围：后端 Spring Boot Demo 全量 InMemory Repository 阶段；前端仅派单 payload / DTO 微调
> 对应审计：`docs/demo-architecture-review.md`（结论 READY WITH FIXES）、`docs/database-design/database-architecture-review-findings.md`
> 本阶段**未**连接 openGauss、**未**执行 SQL、**未**引入 JPA/MyBatis/Flyway/Liquibase、**未**修改数据库设计文档与 DDL 草案、**未**加 `@Transactional`、**未**重构 Repository 契约（唯一允许：把构造器隐式灌种迁出）。

## 0. 修复顺序与结论

按要求严格串行，每步编译/测试通过后再进入下一步：

1. 机器 Code 体系（F-01）
2. assignee 落 userCode（F-02）
3. Rule / Fence provenance（F-07）
4. Timeline 结构化 eventType / sequenceNo（F-12）
5. 时间类型收口 OffsetDateTime（F-18）
6. Device Master Data 归口（设备主数据双源）
7. Seeder / Simulator profile 隔离（F-11）
8. 全量回归（后端 158 tests 全绿；前端 typecheck / 117 tests / build 全过）

## 1. 机器 Code 体系（F-01）

### 1.1 总原则

- **内部判断 = code，外部展示 = label**。Service / Repository filter / Projection 分组统计只允许使用机器 code；中文只允许出现在 label 映射、Seed 装配、Response 展示、前端。
- 不引入 enum（与项目现有常量类风格一致），不建巨型 GlobalStatusUtil；按领域分置各 model 包。
- 每个 code 类统一形态：`final class` + private 构造 + `String` 常量 + `Map<code,label> LABELS` + 反转 `BY_LABEL` + 静态 `label(code)` / `fromLabel(label)` / `normalize(codeOrLabel)`；未知 code/label 返回 `null`（调用方展示“未知”并告警，不猜测替换）。
- API 兼容：Model 删除中文字段、改为 `xxxCode` 权威字段，通过派生 getter（`@JsonProperty("status") getStatus()` 等）继续输出原中文 JSON 字段，同时新增 `xxxCode` JSON 字段。**前端现有中文展示不破坏。**
- Repository 查询入参对中文/code 都 `normalize()` 兼容；WS payload 同时放中文与 code。

### 1.2 各域 Code 集合

| 域 | code 类 | 机器 code → 中文 label |
| --- | --- | --- |
| Alert 风险 | `alert.model.RiskLevels` | NORMAL 一般 / WARNING 预警 / SEVERE 严重 / URGENT 紧急；含 ORDERED、rank/higher/isHigh（SEVERE+ 为高风险） |
| Alert 状态 | `alert.model.AlertStatuses` | PENDING_CONFIRM 待确认 / CONFIRMED 已确认 / PENDING_ASSIGNMENT 待派单 / PENDING_PROCESS 待处理 / PROCESSING 处理中 / PENDING_REVIEW 待复核 / CLOSED 已关闭 / ESCALATED 已升级；ACTIVE 集合；旧 TO_* / HANDLING 常量删除 |
| 判定来源 | `alert.model.DecisionSources` | FENCE / RULE / AI_MODEL / COLLISION / EDGE / MANUAL |
| AI 风险 | `ai.model.AiRiskLevels` | LOW 低 / MEDIUM 中 / HIGH 高；`toAlertRiskCode()` 集中映射到 Alert 四级（**不与 Alert 风险词表合并**） |
| AI 复核状态 | `ai.model.AiReviewStatuses` | PENDING 待复核 / CONFIRMED 已确认违规 / FALSE_POSITIVE 误报 / UNCERTAIN 不确定 / ASSIGNED 已派单 / PROCESSING 处理中 / CLOSED 已关闭；isConfirmedChain |
| 人员在线 | `personnel.model.PersonStatuses` | ONLINE / OFFLINE |
| 手环状态 | `personnel.model.BraceletStatuses` | ONLINE / OFFLINE / LOW_BATTERY |
| 人员风险 | `personnel.model.PersonRiskLevels` | NORMAL 正常 / ATTENTION 关注 / HIGH 高风险（与 Alert 四级不同义，不合并） |
| 碰撞风险 | `collision.model.CollisionRiskLevels` | SAFE 安全 / WARNING 预警 / SEVERE 严重 / URGENT 紧急；`toAlertRiskCode()` 映射 Alert 风险 |
| 感知健康 | `collision.model.SensorHealth` | NORMAL 正常 / UNCERTAIN 待确认 / RADAR_DOWN 雷达故障 |
| 围栏状态 | `fence.model.FenceStatuses` | DRAFT / TO_REVIEW / TO_PUBLISH / EFFECTIVE 已生效 / DISABLED / MISMATCH |
| 规则状态 | `rule.model.RuleStatuses` | DRAFT 草稿 / REVIEW 待评审 / APPROVED 已批准 / PUBLISHING / ACTIVE 已生效 / MISMATCH 版本异常 / DISABLED 已停用；EDGE_SYNCED / EDGE_SYNCING / EDGE_MISMATCH 保持英文 |
| 边缘节点 | 既有 `EdgeStatuses` | ONLINE / DEGRADED / OFFLINE / RECOVERING / ERROR（本就是 code，不动） |
| 边缘待处理事件 | 既有 code | PENDING / 本域既有状态（本就是 code，不动） |

关键语义修正：**Collision 的“待确认”不是碰撞风险等级，而是雷达/感知健康（SensorHealth.UNCERTAIN）**，风险词表与健康词表彻底分开。

### 1.3 允许保留中文的位置（不是遗漏）

- label 映射表本身、Seed 装配期一次性 `fromLabel/normalize` 转换（注释标明非运行时判断）；
- `DemoFence.kind`（危险区域 / 预警区域，围栏种类展示值）；
- AI priority 透传展示串（“紧急”/“普通”）；
- EdgeOps 本地事件模板 / PayloadSummary / EdgePendingEvent.risk 中文标签链路（由 EdgeReplayService `RiskLevels.normalize` 兜底，本阶段明确不处理，列入后续技术债）；
- 各类遥测中文状态（plcStatus / radarStatus / communication）。

完成后 Service 业务判断中的中文字面量基本为 0；Projection（RiskClassifier / SafetyProjectionService / ScreenService / OverviewService / AnalyticsService / MobileService / EdgeOpsService）全部改为 code 判断，DTO 仍经 getRisk()/getStatus() 输出中文。

> 口径差异说明：大屏 `SafetyProjectionService` 的“处置中”= PROCESSING + PENDING_REVIEW + ESCALATED；移动端 `MobileService` 的 handling = PROCESSING + ESCALATED（待复核归复核队列，基线测试 mobileHome handling=3）。两者是不同页面口径，均已用 code 表达，不做强行统一。

## 2. assignee 责任人落 userCode（F-02）

### 2.1 后端

- `DemoAlert`：权威字段 `assigneeUserCode`（USR-*），`assignee` 为**姓名快照**（JSON 字段名保持 `assignee`）。
- `AlertRequests`：
  - `AssignRequest(assignee, assigneeId, assigneeName, priority, limitMin, slaLimitMin, deadline, note)`
  - `TransferRequest(assignee, assigneeId, note, operator)`
- `AlertService` 注入 `DemoMasterData`，assign / transfer 经私有 `resolveAssignee(userCode, name)`：
  - 优先用 `assigneeId`（USR-*）经 `DemoMasterData.resolveUser` 核验存在且有效；
  - code 位填姓名、或旧客户端只传唯一姓名时兼容匹配（Deprecated 路径）；
  - “待分配”特判为未分配；无效 / 重名无法唯一匹配 → **422**「派单必须指定有效责任人（userId 或唯一姓名）」；
  - 落库前从主数据读取姓名，写 `assigneeUserCode` + 姓名快照，**不信任前端传入姓名**。
- `AiRequests.AiAssignRequest` 新增 `assigneeId`（可选，旧字段兼容）；`AiEventService.assign` 用 masterData 核验，写 `event.assigneeUserCode`，再构造 Alert `AssignRequest` 时统一传同一个 USR-* ID，缺省兜底 USR-002 王建国。AI 侧不再维护第二份名单。

### 2.2 前端（仅派单 payload / DTO 微调，不重做页面）

- AI 派单 `components/ai/AssignmentDialog.vue` emit 增加 `assigneeId`；`api/aiEvents.ts` payload 增加 `assigneeId?`；`AiReviewView.vue` 透传。
- Alert 派单 `AlertAssignDialog.vue` 早已 emit `assigneeId`；`api/alerts.ts` 注释明确 ID 权威，TransferPayload 增加 `assigneeId?/assigneeName?`；`AlertCenterView.vue` 转派补传 ID。
- 两个派单弹窗的人员列表都来自全局 Dictionary Store（`/meta/dictionaries` → Demo Master Data），无本地 fallback；字典失败显示“人员数据加载失败”+重试。

### 2.3 列表筛选同步支持 userCode

- `InMemoryAlertRepository.filter` 的 `assignee` 条件由“仅等于姓名快照”改为：等于 `assigneeUserCode`，或姓名快照 `contains` 查询值（兼容 Seed 快照中“安全员 王建国”这类角色前缀）。
- 浏览器回归实测：`GET /alerts?assignee=USR-004` 与 `?assignee=陈静` 均能命中；新增集成测试 `filterByAssigneeUserCode`。

## 3. Rule / Fence provenance（F-07）

- 问题原状：人员越界建 Alert 时写死 `ruleId=RULE-PER-001`、`ruleVersion=fence.version`，把**围栏版本伪装成规则版本**。
- `DemoAlert` 新增轻量来源三字段（不引入决策引擎/规则运行时框架）：
  - `decisionSourceType`（DecisionSources：FENCE / RULE / AI_MODEL / COLLISION / EDGE / MANUAL）
  - `decisionSourceCode`（如 FENCE-003 / RULE-PER-001 / 模型标识）
  - `decisionSourceVersion`（如围栏 v1.2 / 模型版本）
- `PersonnelService.intrude`：越界由围栏 Point-In-Polygon 判定，provenance = `FENCE + fence.id + fence.version`，`ruleId/ruleVersion = null`；危险围栏风险取围栏 riskCode（normalize），预警围栏统一 WARNING。
- `AlertService.applyProvenance`：仅 RULE 来源才回填 ruleId / ruleVersion。
- Collision demoRisk：provenance = RULE / RULE-PER-001 / v3.3（确由规则触发的路径才写 rule 字段）。
- `EdgeReplayService.toDraft`：按 eventType 映射 provenance（collision-risk→COLLISION；ppe-violation→AI_MODEL；person-stay/default→FENCE，sourceCode 取 fence、sourceVersion 取 edge 的 ruleVersionUsed），ruleId/ruleVersion 传 null。
- AI 来源继续保留 modelVersion 作为模型证据。
- 未决项不动：FENCE-003 `teamScope` 自由文本含“检修班、外协单位”，UNRESOLVED，不建 FK、不强行映射。

## 4. Timeline 结构化（F-12）

### 4.1 Alert Timeline

- `TimelineEvent` 由 4 参扩为 6 参 record：`(time, at, text, state, eventType, sequenceNo)`，保留 4 参兼容构造（eventType/seq=null，供历史 Seed 展示节点）；`@JsonInclude(NON_NULL)`。
- 新增 `AlertTimelineEventTypes`（17 个）：
  CREATED、CONFIRMED、ASSIGNED、TRANSFERRED、ACCEPTED、ARRIVED、STARTED、TREATMENT_SUBMITTED、REVIEW_APPROVED、REVIEW_REJECTED、ESCALATED、LINKAGE_STARTED、LINKAGE_FAILED、TAKEOVER、CLOSED、EDGE_REPLAY、NOTE。
- `AlertService.addEvent` 显式带 eventType，sequenceNo 取当前最大序号 +1；每次 mutate 后 `stampSequences` 保证同一 Alert 内序号从 1 连续、严格递增（Seed / 建单阶段未编号节点按顺序补 NOTE + 编号），不再依赖 Java List 偶然顺序。
- 中文展示文案 `text` 全部保留。

### 4.2 AI Timeline

- `AiTimelineNode` 扩为 5 参 `(time, text, state, eventType, sequenceNo)`，新增 `AiTimelineEventTypes`：DETECTED、QUEUED、CONFIRMED、FALSE_POSITIVE、UNCERTAIN、ASSIGNED、PROCESSING、CLOSED、ALERT_LINKED、NOTE。
- `AiEventService` 4 参 appendTimeline，工作流节点分别标 CONFIRMED / ALERT_LINKED / FALSE_POSITIVE / UNCERTAIN / ASSIGNED / PROCESSING / CLOSED。AI 与 Alert 保持各自 Timeline，不合表、不共用类。
- AiDemoSeeder 中纯展示的历史检测节点仍用 3 参（eventType=null，经 stamp 后归 NOTE），不影响闭环统计。

### 4.3 Analytics 去中文关键字

- `AnalyticsService` 的 confirmSeconds / arriveSeconds / closeSeconds 改为按 eventType 集合匹配（CONFIRMED；ACCEPTED+ARRIVED；CLOSED），`secondsToFirst` 入参由 `Pattern` 改为 `Set<String>`，不再 `text.contains("开始处理")` 等中文关键字匹配。

## 5. 时间类型收口（F-18）

- 原则：参与逻辑 / 需要持久化的时间权威类型为 `OffsetDateTime`；`HH:mm:ss` / `yyyy-MM-dd` 等仅作为 Response/ViewModel 展示快照。
- Jackson 已配置 `write-dates-as-timestamps=false`、time-zone `Asia/Shanghai`，API 权威时间输出 ISO-8601。
- 唯一参与逻辑的 String 时间判断 `InMemoryAiEventRepository.matchTimeBucket` 已改用 `occurredAt.atZoneSameInstant(Asia/Shanghai).toLocalTime()` 比较 21:00 / 22:00 时段。
- `CollisionService.PairView.of` 增加 ts 参数，pair() 传 `OffsetDateTime.now(clock)` 的 ISO 值。
- 统一经 `Clock` Bean 取现在；业务代码不再散落 `OffsetDateTime.now()`（基础设施 ApiError、InMemory 存储层 updatedAt 默认值除外，已在文档说明）。
- 审计后**保留为展示快照**的 String 字段（不参与逻辑、与 OffsetDateTime 并存或仅展示）：
  - DemoFence.effectiveAt / expiresAt；DemoRule.effectiveAt / updatedAt、Version.date（yyyy-MM-dd）
  - DemoCollisionDevice.lastUpdated、DemoPersonnel.lastUpdated（“刚刚”/HMS，均有 OffsetDateTime updatedAt 并存）
  - DemoAiEvent.time / confirmTime / acceptTime / reviewTime（HMS 展示）
- 明确区分 Edge occurred time / server received time / sync time（occurred_at / received_at / synced_at），不统称 created_at——该区分在数据库蓝图中落实，本阶段不改 API。

## 6. Device Master Data 归口（设备主数据双源）

- 新增 `com.bproject.safety.support.masterdata.DemoDeviceMasterData`（@Component，构造注入 DemoMasterData）：
  - record `DeviceIdentity(code, name, type, areaCode, areaName, category)`；分类常量 CAT_CAMERA / CAT_COLLISION / CAT_EDGE。
  - 静态身份：8 路摄像头 CAM-01..08、4 台防碰撞设备（VEH-07 / VEH-08 / TIP-02 / CRANE-01）、4 个边缘节点 EDGE-01..04；areaName 由 DemoMasterData 解析。
  - 方法：cameras() / collisionDevices() / edgeNodes() / device(code)。
  - **只存身份/静态属性**（code/name/type/area/capability），不存 online/speed/risk/cpu/queueDepth 等运行态。
- 消费方收口：
  - AI：`AiDemoSeeder.buildCameras(DemoDeviceMasterData)` 静态方法，身份从主数据取，仅保留画质/健康运行态；`AiEventService` 构造注入设备主数据。
  - Collision：`InMemoryCollisionRepository` 构造注入主数据，reset()/seed() 只传运行态。
  - Ops：`OpsInventory` 构造注入主数据，CAM-01..08 用主数据 name/area 覆盖合成数据（CAM-09..24、RAD/LOC/PLC/ALM 等运维台账保留）。
  - Edge：`InMemoryEdgeNodeRepository` 构造注入主数据，节点身份（含 IP）从主数据取。
- 测试保证同 code 不同模块 name/area 完全一致（MasterDataConsistencyTest + PhaseA 专项）。

## 7. Seeder / Simulator profile 隔离（F-11）

### 7.1 配置开关（安全默认关闭）

`application.yml`：

```yaml
app:
  demo:
    seed-enabled: ${APP_DEMO_SEED_ENABLED:false}
    simulator-enabled: ${APP_DEMO_SIMULATOR_ENABLED:false}
```

- `application-dev.yml`：两项经环境变量默认 **true**（本地 Demo 开箱即用）。
- `application-server.yml`：**不设置**，继承安全默认 false；服务器仍需 Demo 时必须显式 `APP_DEMO_SEED_ENABLED=true` / `APP_DEMO_SIMULATOR_ENABLED=true`。
- 测试资源 `application-test.yml`：两项 true（集成测试依赖种子）。

### 7.2 灌种路径收口

- 新增 `support.demo.DemoFeatureGuard`（@Component）：isSeedEnabled / isSimulatorEnabled / requireSimulator()（关闭时抛 `ApiException.forbidden("DEMO_FEATURE_DISABLED", …)`，HTTP 403）。
- 新增 `support.demo.DemoSeedInitializer`（CommandLineRunner @Order(5)）：seedEnabled 时统一对 Fence / Personnel / Collision / EdgeNode / OpsInventory 执行 reset() 灌种，否则日志跳过。
- **5 个 InMemory 仓库 / 台账构造器不再隐式灌种**（保留 public reset()）：InMemoryFenceRepository、InMemoryPersonnelRepository、InMemoryCollisionRepository、InMemoryEdgeNodeRepository、OpsInventory。
- 3 个 DemoSeeder Runner（AlertDemoSeeder @Order(10)、AiDemoSeeder @Order(11)、RuleDemoSeeder @Order(11)）构造器注入 guard，`run()` 开头 `if (!guard.isSeedEnabled()) return;`。
- Repository 只负责存储；测试需要种子时显式调用 buildSeeds/reset 或 fixture，不依赖启动顺序。

### 7.3 模拟端点门控

9 个 Controller 共 13 处模拟/演示写接口首行 `demoGuard.requireSimulator()`，关闭时 403 DEMO_FEATURE_DISABLED（不是 200 静默成功）：

- AiEventController `/ai-events/simulate`
- AnalyticsController `/simulate-surge`
- CollisionController `/simulate`
- FenceController `/{id}/simulate-mismatch`
- EdgeAutonomyController `/simulate-link`、`/local-events`(POST)、`/recover`
- OperationsController `/simulate`
- PersonnelController `/simulate-abnormal`
- OverviewController `/simulate-risk`
- RuleController `/{id}/simulate-mismatch`、`/simulate`

不门控：所有 GET 查询、Rule `/conflict-check`（真实校验）、Collision `/linkage` `/takeover`（真实业务）、Edge GET `/link` `/local-events` `/reconcile/*`。真实业务查询/处置接口在开关关闭时完全正常。

## 8. API 兼容策略汇总

- 不改变任何 REST 路径与动作语义；不改变状态机。
- 响应同时提供：原有中文字段（status/risk/assignee 等，派生 getter 输出）+ 新增机器字段（statusCode/riskCode/assigneeUserCode/decisionSource* 等）。
- 请求侧新增可选 ID 字段（assigneeId），旧的姓名入参保留 Deprecated 兼容；无效责任人 422。
- 前端本阶段不强制全量改英文化，Adapter 可逐步优先使用 code；中文显示保持不变。
- 后续正式迁移方向（本阶段只记录、不实施）：DDL 按 code 落列、assignee_user_id 正式关联 + 姓名快照、timeline event_type/sequence 落列、timestamptz 权威时间。

## 9. 测试

### 9.1 新增专项测试

- `support/phasea/PhaseASemanticHardeningTest`（纯手工装配，13 个用例，分 4 组）：
  - CodeSystem：Alert 风险/状态、AI 风险与状态、Collision 风险 vs 感知健康拆分、Fence/Rule 状态的 code↔label、未知 code 返回 null；
  - AssigneeCodes：派单存 code+姓名快照（前端传错名字不被采信）、旧姓名兼容、不存在 userId→422、转派存 code；
  - F-07：人员越界 Alert 的 decisionSourceType=FENCE、sourceCode 为 FENCE-*、ruleId/ruleVersion 为 null；
  - F-12：confirm→assign→start→treatment 全闭环每个 Timeline 节点 eventType 非空、sequenceNo 从 1 严格递增、关键事件类型齐全；
  - 设备主数据：Camera / Collision / OpsInventory 同 code 的 name/area 与 DemoDeviceMasterData 一致；
  - F-11：DemoFeatureGuard 关闭时 requireSimulator 抛 403 code DEMO_FEATURE_DISABLED。
- `support/phasea/DemoFeatureDisabledIntegrationTest`（@SpringBootTest，seed/simulator=false）：
  - 启动后 Alert / Personnel / Fence / Collision 仓库全部为空（不隐式灌种）；
  - POST `/api/v1/collision/simulate` 返回 403 且 `$.code = DEMO_FEATURE_DISABLED`。

### 9.2 回归结果

- 后端：`mvn clean test` **Tests run: 158, Failures: 0, Errors: 0**（原 142 + Phase A 新增 15 + 责任人筛选 1）。
- 前端：`vue-tsc -b` typecheck 通过；`vitest run` **29 files / 117 tests 全过**；`vite build` 成功（仅保留既有的 chunk >500kB 警告，本阶段明确不处理 ECharts 分包）。

## 10. 本阶段明确未做（留给后续阶段）

- Repository 接口的 findMutable / reset / 可变对象返回等契约重构（Phase B）；
- 事务协调 / `@Transactional` / Application Orchestrator / Transactional Outbox；
- JDBC Repository、openGauss 连接、DDL 执行、Flyway/Liquibase、JPA/MyBatis；
- 数据库设计文档与 DDL 草案修订（代码语义稳定后下一阶段统一回写）；
- EdgeOps 本地事件中文标签链路（EdgeReplayService normalize 兜底）；
- AI 种子历史检测 Timeline 节点 eventType（纯展示，stamp 后归 NOTE）；
- 状态中文→code 的 API 破坏性迁移（当前双发兼容）；
- F-13 OperationsView 前端 mock、openGauss/Kvrocks dev 探测日志、Vite 分包。
