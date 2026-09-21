# 数据库设计 Phase B.5 Findings（关键缺陷修复后的架构输入）

> 来源：Database Pre-Migration Phase B.5（见《../pre-database-critical-fixes.md》与《../demo-architecture-phase-b5-status.md》）。  
> 性质：**只记录对数据库蓝图的新增/修订输入，不修改** 现有 DDL 草案与设计文档；待数据库设计最终修订阶段统一吸收。  
> 编号：PBF-08 ~ PBF-12（接续 Phase B 的 PBF-01 ~ PBF-07）。

---

## 一、告警状态机模型与升级语义确认（PBF-08，源自 B5-01）

### 1. 代码侧定型事实
- 告警流转中，“升级（Escalation）”被证实为**风险等级（Risk Level）的正交提升**，而非业务处置状态（Status）。
- 告警主状态仅包含：`PENDING_CONFIRM`（待确认）、`PENDING_ASSIGNMENT`（待认领）、`PENDING_PROCESS`（待处置）、`PROCESSING`（处理中）、`PENDING_REVIEW`（待复核）、`CLOSED`（已关闭）、`CANCELLED`（已作废）。原状态枚举 `ESCALATED` 已被彻底废弃。
- 升级操作发生在处置阶段，升级后主状态保持不变（如仍然是处理中），仅将风险等级提升为更高等级（如紧急），同时记录历史等级。

### 2. 数据库设计输入
- **`safety_alert` 表状态列**：`status` 字段的枚举约束（CHECK 约束或字典引用）中，**严禁包含 `ESCALATED`**。
- **历史与升级追踪列**：
  - 在 `safety_alert` 表增加 `previous_risk_level VARCHAR(32)`（升级前风险等级，允许为空）；
  - 或在关联的 `safety_alert_timeline` 表中增加 `event_type = 'RISK_UPGRADED'` 类型的审计记录，在 payload 中存放升级前后的风险等级和操作员；
  - 建议：主表保留轻量列 `previous_risk_level` 便于快速筛选“曾升级告警”，详细升级历史由 timeline 承载。

---

## 二、碰撞对与告警关联关系权威性（PBF-09，源自 B5-02）

### 1. 代码侧定型事实
- `DemoCollisionPair` 中的 `activeAlertId` 字段只是业务运行态的**瞬态缓存引用**，非关系权威。
- 当告警生命周期流转至 `CLOSED` 后，碰撞对若再次满足预警条件，必须自动清空失效引用并生成全新告警。
- 告警防重与生命周期判断的唯一权威依据为：
  `AlertRepository.findOpenByDedupKey("COLLISION:" + deviceA + ":" + deviceB)`

### 2. 数据库设计输入
- **弱化关联设计**：若在 `collision_pair`（或设备碰撞状态表）保留 `active_alert_id` 字段，该列**必须作为可空的弱引用缓存列，绝对不要建立 FOREIGN KEY 物理强约束**，避免告警归档或清理时产生级联异常。
- **权威去重索引**：`safety_alert` 表必须建立覆盖去重键与未关闭状态的高效索引：
  ```sql
  CREATE INDEX idx_safety_alert_dedup_open ON safety_alert (dedup_key, status)
      WHERE status != 'CLOSED' AND status != 'CANCELLED';
  ```
  该部分索引（Partial Index）可显著提升碰撞、越界等高频去重查询的性能。

---

## 三、嵌套事务与事件发布收口边界（PBF-10，源自 B5-03）

### 1. 代码侧定型事实
- 经过 Phase B.5 重构，`LiveEventGate` 已支持嵌套调用，且确立了核心原则：
  - 仅在最外层工作流成功完成时才统一触发外部广播；
  - 任一内层原子事务抛出异常，整个链路的事件被完全丢弃；
  - 单条广播失败属于 best-effort，不得影响已完成的持久化结果。

### 2. 数据库设计输入
- **事务传播标准**：未来在 Spring Boot + openGauss 环境中，各业务 Service 的 `@Transactional` 统一采用默认的 `Propagation.REQUIRED`。内层方法加入外层事务，内层异常抛出将正确标记事务回滚，与 `LiveEventGate` 的 discard 语义完全一致。
- **发布机制落地**：
  - 方案 A（轻量）：使用 Spring `TransactionSynchronizationManager.registerSynchronization` 在 `afterCommit()` 阶段触发 WebSocket 广播；
  - 方案 B（高可靠）：在事务内插入 `outbox_event` 本地表，由后台调度拉取并推送到 RocketMQ。
  - **建议**：当前单实例演进至数据库阶段时，优先采用 `afterCommit` 保持代码精简；`outbox_event` 表在 DDL 草案中保持预留，不强行作为第一步的阻塞条件。

---

## 四、规则多版本生命周期表结构（PBF-11，源自 B5-04）

### 1. 代码侧定型事实
- 编辑处于 `ACTIVE`（已生效）状态的规则，属于“在线编制新版本”，**绝不能直接修改或降级线上正在运行的规则状态**。
- 当前生效版本继续生效、其关联的边缘节点继续保持同步，新版本以草稿（`DRAFT`）形式独立存在，直至发布成功后完成主指针切换。

### 2. 数据库设计输入
- **表结构划分**：必须严格采用 **“主表（`safety_rule`）+ 版本子表（`safety_rule_version`）+ 边缘同步表（`safety_rule_edge_sync`）”** 的模式。
- **字段职责划分**：
  - `safety_rule` 主表：存放规则基础标识、分类、当前生效版本号（`active_version`）、当前主状态（`status`，如 ACTIVE）、生效时间（`effective_at`）；
  - `safety_rule_version` 子表：存放各具体版本（如 v2.4, v2.5）、版本级状态（DRAFT, REVIEW, APPROVED, ACTIVE, ARCHIVED）、参数配置快照（JSONB）、操作人与审批人；
  - `safety_rule_edge_sync` 边缘同步表：记录每个边缘节点当前下发并确认运行的具体版本号（`running_version`）与同步状态（`synced`, `mismatch`）。
- **编辑与发布语义**：
  - 编辑生效规则：向 `safety_rule_version` 插入一条新版本记录（`status = 'DRAFT'`），主表状态与生效时间保持不动；
  - 发布新版本：在同一个数据库事务中，将新版本的子表记录置为 `ACTIVE`、旧版本置为 `ARCHIVED`、更新主表 `active_version` 与 `effective_at`、更新 `safety_rule_edge_sync` 表的目标下发版本。

---

## 二、业务编号生成策略与唯一约束（PBF-12，源自 B5-05）

### 1. 代码侧定型事实
- 业务编号生成器（Alert: `ALM-yyyyMMdd-NNN`、AI Event: `AI-E-yyyyMMdd-NNN`、Rule: `RULE-<DOMAIN>-NNN`、Fence: `FENCE-NNN`）必须具备**单调递增、跳过间隙、探测防碰撞**的确定性。
- 代码中单 JVM 内存遍历与递增探测仅用于 Demo / InMemory 阶段。

### 2. 数据库设计输入
- **物理约束**：所有承载业务编号的字段（如 `safety_alert.alert_no`、`ai_event.event_no`、`safety_rule.rule_code`、`safety_fence.fence_code`）必须显式建立 **`UNIQUE` 唯一索引**。
- **序列生成方案**：
  - 对于按日滚动的编号（如 `ALM-yyyyMMdd-NNN`），建议在 openGauss 中使用按日命名的 Sequence（或通过每日维护的编号计数序列表 `sys_sequence` + `SELECT FOR UPDATE`），在应用层拼装日期前缀与填充格式；
  - 对于固定前缀编号（如 `FENCE-NNN`），可直接对应单个 openGauss `SEQUENCE`；
  - 严禁依赖数据库 `SELECT COUNT(*)` 来生成下一个业务编号。
