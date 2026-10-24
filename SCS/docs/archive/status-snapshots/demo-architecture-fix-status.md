# 架构审计 Finding 修复状态（Database Pre-Migration Phase A）

> 日期：2026-09-20
> 范围：仅登记 Phase A 处理的审计项（F-01 / F-02 / F-07 / F-11 / F-12 / F-18 + 设备主数据双源）。
> 本文**不修改、不重写** `docs/demo-architecture-review.md` 与 `docs/database-design/database-architecture-review-findings.md` 的原始审计事实，只追加修复状态。
> 修复细节见 `docs/pre-database-semantic-hardening.md`。
> 状态定义：**RESOLVED**（本阶段目标已达成）/ **PARTIAL**（主体完成、留有明确边界内的剩余项）/ **OPEN**（未处理）。

## 状态总表

| Finding | 主题 | Phase A 前 | 修复后状态 | 验证方式 |
| --- | --- | --- | --- | --- |
| F-01 | 风险/状态中文串直接做业务判断，无机器 code | 约 20+ 处中文字面量进入 if/filter/grouping | **RESOLVED** | PhaseASemanticHardeningTest.CodeSystem（5）+ 全量 157 测试 |
| F-02 | Alert/AI 派单丢弃 assigneeId，只按姓名关联 | assign 不读 ID；蓝图 assignee_user_id 无值可写 | **RESOLVED** | PhaseASemanticHardeningTest.AssigneeCodes（4）+ Alert/Ai/Projection 集成测试 |
| F-07 | 人员越界把 Fence 版本伪装成 Rule 版本 | ruleId=RULE-PER-001、ruleVersion=fence.version | **RESOLVED** | PhaseASemanticHardeningTest provenance 用例 |
| F-11 | Seeder / simulate 端点无 profile 隔离 | 所有 profile 启动灌种、模拟端点裸奔 | **RESOLVED** | DemoFeatureDisabledIntegrationTest（2）+ DemoFeatureGuard 单测 |
| F-12 | Timeline 无结构化事件类型，Analytics 靠中文文本算时长 | TimelineEvent 仅 time/text/state；text.contains | **RESOLVED** | PhaseASemanticHardeningTest timeline 用例 + AnalyticsModuleIntegrationTest（7） |
| F-18 | 多个时间字段为 String，与 OffsetDateTime 混用 | effectiveAt/expiresAt/lastUpdated/time 等 | **RESOLVED（边界内）** | 全量回归；保留字段均为纯展示快照，见下说明 |
| 设备主数据双源（findings §device_camera） | Camera 台账在 AiDemoSeeder，与 OpsInventory/Collision 双源 | 同设备两套 name/area 来源 | **RESOLVED（Demo 范围）** | PhaseASemanticHardeningTest 设备主数据用例 + MasterDataConsistencyTest（9） |

## 逐项说明

### F-01 机器 Code 体系 — RESOLVED

- 新增/重写各域 code 常量类：RiskLevels、AlertStatuses、DecisionSources、AiRiskLevels、AiReviewStatuses、PersonStatuses、BraceletStatuses、PersonRiskLevels、CollisionRiskLevels、SensorHealth、FenceStatuses、RuleStatuses、AlertTimelineEventTypes、AiTimelineEventTypes。
- Service / Repository filter / Projection（RiskClassifier、SafetyProjectionService、ScreenService、OverviewService、AnalyticsService、MobileService、EdgeOpsService）业务判断全部改用 code。
- API 中文展示通过派生 getter 双发（中文字段 + xxxCode），前端不破坏；未知 code/label 返回 null，不猜测替换。
- 语义修正：Collision“待确认”归入 SensorHealth.UNCERTAIN（感知健康），不再是碰撞风险等级；AI 风险（低/中/高）与 Alert 四级风险保持独立词表，映射集中在 AI 侧。
- 明确白名单（允许保留中文，非遗漏）：label 映射、Seed 装配一次性转换、Fence.kind、AI priority 展示串、EdgeOps 本地事件模板/PayloadSummary（EdgeReplayService normalize 兜底，列为后续技术债）、各类遥测中文状态。

### F-02 assignee 落 userCode — RESOLVED

- DemoAlert：`assigneeUserCode`（USR-*）为权威关联，`assignee` 为姓名快照；AlertService assign/transfer 经 DemoMasterData 核验，无效/重名→422，不信任前端传入姓名。
- AiAssignRequest 新增 assigneeId；AiEventService 统一经 AlertService 派单，AI/Alert 两个弹窗看到同一份 7 人名单、同一 ID。
- 旧客户端只传唯一姓名的路径保留并标记 Deprecated（有测试覆盖）。
- 前端 5 个派单相关文件改为发送 assigneeId（AI AssignmentDialog / api/aiEvents / AiReviewView / api/alerts / AlertCenterView）。
- 未做（本阶段禁止）：API 请求/响应中姓名字段的破坏性移除；未来 DDL 落 assignee_user_id 列在下一阶段。

### F-07 Rule/Fence provenance — RESOLVED

- DemoAlert 新增 decisionSourceType / decisionSourceCode / decisionSourceVersion；人员越界 provenance=FENCE+fence.id+fence.version，ruleId/ruleVersion 为 null；仅 RULE 来源回填 rule 字段；EdgeReplay 按事件类型映射来源。
- 关联但不属于本 finding 的 UNRESOLVED 主数据项保持不动：FENCE-003 teamScope 自由文本含“检修班、外协单位”（demo-master-data.md 已登记，不建 FK、不强行映射）。

### F-11 Seeder/Simulator 隔离 — RESOLVED

- 安全默认：`app.demo.seed-enabled` / `app.demo.simulator-enabled` 默认 false；dev profile 默认 true；server profile 继承 false，需显式环境变量 `APP_DEMO_SEED_ENABLED` / `APP_DEMO_SIMULATOR_ENABLED` 开启。
- 5 个仓库/台账构造器不再隐式灌种，统一由 DemoSeedInitializer（@Order(5)）在开关开启时执行；3 个 DemoSeeder Runner 受 guard 门控。
- 9 个 Controller 共 13 处模拟端点加 requireSimulator()，关闭返回 403 DEMO_FEATURE_DISABLED；真实查询/处置接口不受影响。
- 新增 disabled-profile 集成测试：关闭时仓库为空、模拟端点 403。

### F-12 Timeline 结构化 — RESOLVED

- Alert TimelineEvent 增 eventType（17 类）+ sequenceNo（同 Alert 从 1 连续严格递增，stampSequences 兜底）；AI AiTimelineNode 增 eventType（10 类）+ sequenceNo，两条 Timeline 不合表。
- AnalyticsService confirm/arrive/close 时长改为按 eventType 集合计算，删除中文关键字匹配。
- 保留：中文 text 展示文案；AiDemoSeeder 纯展示历史检测节点 eventType=null（stamp 后归 NOTE），不影响闭环统计。

### F-18 时间类型收口 — RESOLVED（边界内）

- 参与逻辑/需持久化的时间权威类型统一为 OffsetDateTime；matchTimeBucket 改用 occurredAt 带时区转 LocalTime；PairView ts 由 Clock 生成；Jackson 统一 ISO-8601 / Asia/Shanghai。
- 经审计保留为**纯展示快照**的 String 字段（不参与逻辑，不影响落库设计）：Fence.effectiveAt/expiresAt、Rule.effectiveAt/updatedAt、Version.date、Collision/Personnel 的 lastUpdated（与 OffsetDateTime updatedAt 并存）、AI time/confirmTime/acceptTime/reviewTime（HMS）。数据库蓝图中这些展示串不进权威时间列。

### 设备主数据双源 — RESOLVED（Demo 范围）

- 新增 DemoDeviceMasterData（Camera 8 / Collision 4 / Edge 4 静态身份，areaName 由 DemoMasterData 解析）；AiDemoSeeder.buildCameras、InMemoryCollisionRepository、OpsInventory（CAM-01..08）、InMemoryEdgeNodeRepository 统一消费；运行态仍归各自 Repository/台账。
- 未覆盖（明确不在本阶段）：F-13 OperationsView 前端 `mock/opsData.ts` 常驻 mock（MEDIUM，后续阶段）；OpsInventory 合成的 CAM-09..24、RAD/LOC/PLC/ALM 为运维演示台账，不属于现场设备主数据冲突。

## 仍未处理的主要 Finding（不在 Phase A 范围，状态保持 OPEN）

以下仅提示，不在本次修复范围，状态沿用原审计：

- Repository 契约（findMutable/reset/可变对象返回）、跨模块事务协调、Transactional Outbox —— 计划在 Phase B（Repository Migration）处理。
- F-13 OperationsView 前端 mock 常驻；F-17 幂等仅覆盖 Alert/AI 写接口。
- F-05/F-06 等 latestAlertId、activeAlertIds、mobileStage 派生/子状态字段的落库去留（数据库 findings 已记录，待设计修订时决策）。
- 状态/风险 API 的中文→code 破坏性迁移、正式 IAM、GIS/对象存储、高频时序进 openGemini 边界等 Open Questions。

## 回归基线

- 后端 `mvn clean test`：Tests run: **158**, Failures: 0, Errors: 0（原 142 + Phase A 新增 15 + 责任人 userCode 筛选 1）。
- 前端：typecheck 通过；vitest **29 files / 117 tests** 全过；vite build 成功（仅余既有 chunk>500kB 警告，本阶段不处理）。
- 未连接 openGauss、未执行 SQL、未修改数据库设计文档与 DDL 草案、未改 pom、未引入 ORM/迁移工具、未加 @Transactional。
