# Repository → 数据库表映射与事务契约（openGauss 6.x 最终修订版）

> 基于 Phase B 与 Phase B.5 核心代码事实：
> - **9 个 Repository 接口 + 9 个 InMemory 实现**：已全面实现“聚合根完整保存（Aggregate Save）”、“深拷贝防污染（Deep-Copy Detached Models）”与“去重/幂等权威收口”。
> - **测试/辅助组件归零**：`DemoResettableStore` 与 `DemoAlertMaintenance` 仅为演示与测试运行态重置机制，**零数据库映射**。
> - **Projection 服务不建表**：`OverviewService` / `ScreenService` / `AnalyticsService` / `MobileService` / `SafetyProjectionService` 均为 SQL 聚合视图，不持有独立事实表。
> - **事件网关解耦**：`LiveEventGate` 嵌套重入已收口，JDBC 阶段通过 Spring `TransactionSynchronization.afterCommit` 钩子或 Outbox 发件箱触发，不影响持久化实体。

---

## 1. 仓储接口与物理实体映射总览

| # | Repository 接口 | 权威物理主表 | 子表 / 明细表级联（同事务写入） | 事务模型要求 | 迁移难度 |
| :-: | :--- | :--- | :--- | :--- | :-: |
| 1 | `AlertRepository` | `safety.safety_alert` | `safety_alert_timeline`<br>`safety_alert_evidence`<br>`safety_alert_treatment`<br>`safety_alert_linkage`<br>`safety_alert_linkage_step` | **强一致事务**<br>（每次业务写均级联更新主子表） | **HIGH** |
| 2 | `AiEventRepository` | `safety.ai_event` | `safety.ai_event_timeline` | **跨聚合事务**<br>（违规确认时与 Alert 协同） | **MEDIUM** |
| 3 | `PersonnelRepository`| `safety.safety_personnel` | *无 openGauss 子表*<br>（连续轨迹划归 openGemini） | **弱/单表事务**<br>（状态与最新坐标 upsert） | **LOW** |
| 4 | `FenceRepository` | `safety.safety_fence` | `safety_fence_version`<br>`safety_fence_edge_sync` | **强一致事务**<br>（版本发布/下发同步） | **MEDIUM** |
| 5 | `CollisionRepository`| `safety.collision_device`| *无 openGauss 子表*<br>（高频毫米波测距划归 openGemini）| **弱/单表事务**<br>（告警建单交由 AlertRepository 权威）| **MEDIUM** |
| 6 | `RuleRepository` | `safety.safety_rule` | `safety_rule_area`<br>`safety_rule_version`<br>`safety_rule_edge_sync` | **强一致事务**<br>（版本管理与下发同步） | **MEDIUM** |
| 7 | `EdgeNodeRepository` | `safety.edge_node` | *无 openGauss 子表*<br>（高频指标序列划归 openGemini） | **弱/单表事务**<br>（节点心跳与运行态更新） | **LOW** |
| 8 | `EdgeEventQueueRepository`| `safety.edge_pending_event`| *联动 safety_alert 补传建单* | **强一致事务**<br>（断网补传逐条原子重放） | **HIGH** |
| 9 | `OpsEventLogRepository`| `safety.ops_event_log` | *无子表*（Append-only 日志流水） | **弱一致/单表追加** | **LOW** |
| — | `DemoResettableStore` | *无* | *仅测试重置上下文，零数据库映射* | — | — |
| — | `DemoAlertMaintenance` | *无* | *仅测试环境重置辅助，零数据库映射* | — | — |
| — | `OpsInventory` | *动态组装* | 读 `collision_device` / `edge_node` / `device_camera` | 只读聚合 | LOW |

---

## 2. 核心 Repository 契约与 SQL 行为演化

### 2.1 `AlertRepository` → `safety_alert` 集群（HIGH）
- **聚合保存契约（Aggregate Save）**：
  - `save(DemoAlert alert)` 承载整个告警生命周期的持久化。
  - 在 JDBC 阶段，`save` 方法必须在单一数据库本地事务中执行：
    1. `UPSERT INTO safety_alert`（使用 `lock_version` 乐观锁防多端并发覆盖）；
    2. 增量插入 `safety_alert_timeline`（依据 `sequence_no` 严格保序）；
    3. 插入/同步 `safety_alert_evidence`（单表多态 JSONB payload）；
    4. 插入/同步 `safety_alert_treatment`（现场处置措施与结论）；
    5. 插入/同步 `safety_alert_linkage` 与 `safety_alert_linkage_step`（联动会话与步骤）。
- **Phase B.5 关键代码事实落地**：
  - **B5-01（告警升级只升风险不改主状态）**：正式主状态集彻底废除 `ESCALATED`。执行 `escalate` 时，仅更新 `risk_level_code`（例如从 `WARNING` 升级到 `SEVERE`），并在 `previous_risk_level_code` 中记录升级前机器代码，主状态 `status_code` 保持为 `PENDING_CONFIRM` 或原处理中状态不变。
  - **B5-02（去重判重权威）**：`findOpenByDedupKey(String dedupKey)` 为去重权威，通过部分索引 `idx_safety_alert_dedup_open` 查询未闭环告警。
- **查询契约**：
  - `list(AlertQuery)`：支持 `status_code`, `risk_level_code`, `area_code`, `occurred_at` 组合索引分页。
  - `findById(UUID id)`：关联加载全量子表并深拷贝重构聚合根。

### 2.2 `AiEventRepository` → `ai_event` 集群（MEDIUM）
- **持久化边界**：
  - 主表 `ai_event` + 子表 `ai_event_timeline`。
  - 视觉识别目标框以 JSONB 列 `detection_boxes` 存储。
- **跨聚合事务**：
  - 执行 `POST /ai-events/{id}/confirm`（确认违规并派单）时，Service 跨 `AiEventRepository` 与 `AlertRepository`：
    1. 在同一个事务中生成并保存 `safety_alert`（`decision_source_type='AI_MODEL'`）；
    2. 更新 `ai_event` 的 `status_code='CONFIRMED'` 并回写 `linked_alert_id = alert.getId()`；
    3. 双方时间线同步追加审计节点。

### 2.3 `PersonnelRepository` → `safety_personnel`（LOW）
- **持久化边界**：
  - 维护在册人员的最新作业态势。
  - 字段全面正交：`online_status_code`（在岗态）、`bracelet_status_code`（终端态）、`person_risk_code`（业务风险感知态）。
  - `person_state` 由前端或查询视图实时推导，不作为物理持久列。
- **非数据库时序红线**：
  - `track()` 端点轨迹点序列为即时确定性算法生成，正式大规模轨迹接入划归 **openGemini**，禁止回流入 openGauss。

### 2.4 `FenceRepository` → `safety_fence` 集群（MEDIUM）
- **聚合保存契约**：
  - 主表 `safety_fence` 维护当前生效版本指针 `active_version`。
  - 子表 `safety_fence_version` 记录历史版本的多边形顶点（`polygon` JSONB）与管控班组（`team_scope` JSONB）。
  - 子表 `safety_fence_edge_sync` 记录该围栏向各边缘节点的下发状态。
- **事务边界**：
  - 围栏发布（`publish`）在单事务内完成：更新主表 `status_code='EFFECTIVE'`，向 `safety_fence_version` 固化版本，向 4 个边缘节点批量插入/更新 `safety_fence_edge_sync`。

### 2.5 `CollisionRepository` → `collision_device`（MEDIUM）
- **Phase B.5 / Patch 4 核心事实修正（B5-02）**：
  - **彻底删除 `active_alert_id` 持久列**：单设备可同时参与多个 Pair（如 VEH-07 同时与 TIP-02、CRANE-01 靠近），设备表上的单一列存在 1:N 基数冲突；`activeAlertId` 仅为运行态缓存镜像。
  - 防碰撞建单去重与开放告警查询完全收敛于 `AlertRepository.findOpenByDedupKey(dedupKey)`。
  - `risk_level_code` 与 `sensor_health_code` 完全正交，不再出现 `UNCERTAIN` 污染风险字段。
- **时序边界**：
  - 毫米波高频雷达点阵与两两测距序列（10~50Hz）划归 **openGemini**，不写入关系型数据库。

### 2.6 `RuleRepository` → `safety_rule` 集群（MEDIUM）
- **Phase B.5 / Patch 3 核心事实修正（B5-04）**：
  - **编辑 `ACTIVE` 规则不降级主表状态**：主表 `safety_rule.status_code` 保持为 `ACTIVE`，`active_version` 维持线上运行版本（如 `v2.4`，未发布的新建规则允许为 `NULL`）；系统在 `safety_rule_version` 中新增一条 `status_code='DRAFT'` 的新版本（如 `v2.5`），更新 `platform_version='v2.5'`。
  - 只有在执行 `publish` 时，主表 `active_version` 才切换为新版本，并同步触发 `safety_rule_edge_sync`。
- **多值关系**：适用区域以 `safety_rule_area` 关系表持久化，支持基于区域索引的高效检索。

### 2.7 `EdgeNodeRepository` → `edge_node`（LOW）
- **持久化边界**：
  - 边缘硬件资产台账与最新心跳/时钟偏差快照。
  - 5 阶段恢复状态机推进与时钟对账伴随 `ops_event_log` 追加。
  - CPU/内存/磁盘高频监控指标划归 openGemini。

### 2.8 `EdgeEventQueueRepository` → `edge_pending_event`（HIGH）
- **Phase B.5 / Patch 5 核心事实修正（B5-05 & 契约）**：
  - `idempotency_key` 施加全局物理 `UNIQUE` 约束。
  - 显式增加 `payload_hash` 列（SHA-256），检测“同键不同载荷”直接报 409 冲突。
  - 合法业务事件 `edge_occurred_at TIMESTAMPTZ NOT NULL` 必填。
  - 稳定重放查询契约：`findPendingForReplay(edgeNodeCode)` 严格按 `edge_occurred_at ASC, event_id ASC` 排序拉取，时间相同时以 `event_id` 作为确定性 tie-break。
- **补传事务边界**：
  - 逐条原子重放（`replay`）：单个离线事件建单时，在同一本地事务中创建 `safety_alert`（标记 `origin='EDGE_REPLAY'`）、写入 `safety_alert_timeline`，并将 `edge_pending_event.status_code` 从 `PENDING` 置为 `SYNCED`，回写 `linked_alert_id`。

### 2.9 `OpsEventLogRepository` → `ops_event_log`（LOW）
- **持久化边界**：
  - 系统与边缘运维审计日志流水。
  - Append-only 写入，无物理更新和物理删除。
  - 历史归档策略替代 Demo 内存版的 `MAX_KEEP` 截断。

---

## 3. 内存辅助组件与 Projection 边界明晰

### 3.1 零数据库表映射组件（Demo Infrastructure）
- **`DemoResettableStore`**：
  - 接口职责：为测试套件和演示前端提供统一重置上下文（`reset()`）。
  - 数据库映射：**零表映射**。在正式生产环境中，数据以数据库物理持久化为准，不需要保留批量清除逻辑。
- **`DemoAlertMaintenance`**：
  - 接口职责：Demo 运行期间模拟定时清理或测试状态干预。
  - 数据库映射：**零表映射**。正式调度由定时任务框架执行标准 SQL 处理。

### 3.2 Projection 服务的 SQL 聚合映射
以下服务均不建独立事实表，未来直接通过 SQL 聚合查询实现：
- `AnalyticsService`：通过 `COUNT(DISTINCT ...)`, `GROUP BY event_type, risk_level_code` 从 `safety_alert` 动态聚合生成数据看板。
- `OverviewService` / `ScreenService`：从 `safety_alert`, `safety_personnel`, `collision_device` 实时查询活跃状态与点位。
- `MobileService`：通过 `assignee_user_code` 与 `status_code <> 'CLOSED'` 查询责任人专属待办列表。

---

## 4. 事务与事件发布（Transaction & LiveEvent Gate）

- **Phase B.5 核心事实（B5-03）**：
  - `LiveEventGate` 已支持嵌套可重入与顶层批次提交。
  - **JDBC 迁移规范**：
    - 严禁在数据库本地事务提交前触发 WebSocket 广播；
    - 必须注册 `TransactionSynchronizationManager.registerSynchronization` 的 `afterCommit` 钩子；
    - 事务提交成功后，从线程局部上下文中取出待发布事件推入 WebSocket；若事务回滚，事件自动丢弃。
    - 跨实例生产架构中，通过将事件同步写入 `outbox_event` 发件箱表，由独立投递器发送至 RocketMQ。
