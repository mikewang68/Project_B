# 数据库设计 Phase B Findings（Repository 契约硬化后的反向输入）

> 来源：Database Pre-Migration Phase B（见《../pre-database-repository-hardening.md》）。
> 性质：**只记录对数据库蓝图的新增/修订输入，不修改** database-design / data-dictionary / ER / repository-mapping / api-mapping / open-questions / architecture-review-findings 与 DDL 草案；待人工评审后，在数据库设计最终修订阶段统一吸收。
> 编号：PBF-xx（Phase B Finding）。

## 一、聚合持久化语义已在代码侧定型（PBF-01）

Phase B 后，Repository 契约已经明确"聚合整体 save"，数据库设计修订时应与此对齐：

- **safety_alert 聚合簇**：一次 `AlertRepository.save` 对应主表 + `safety_alert_timeline` + `safety_alert_evidence` + `safety_alert_treatment` + `safety_alert_linkage(_step)` 的级联写入（JDBC 实现内部决定语句顺序，须在同一事务）。Service 不会出现只写 timeline 不写 alert 的路径，因此**不需要**向 Service 暴露子表 Repository。
- **ai_event 聚合簇**：主表 + `ai_event_timeline`；AI 检测框 boxes 当前为检测快照，建议随事件 JSONB 或证据附件引用，不单独建业务表（与 Evidence 策略一致）。
- **safety_fence 聚合簇**：主表 + `safety_fence_version` + `safety_fence_edge_sync`；polygon 为 JSONB（Point 列表，百分比坐标语义不变）。
- **safety_rule 聚合簇**：主表 + `safety_rule_version`（params/actions JSONB、versionSnapshots 语义）+ `safety_rule_edge_sync`。
- **edge_pending_event**：单表 + JSONB（payloadSummary/localLinkage）；`failNextReplay` 是测试/Simulator 的 transient 标记，**不落库**。

## 二、补传幂等：唯一约束与 CONFLICT 语义（PBF-02）

- 代码侧判重权威已从 JVM HashSet 移到仓储查询：自身 SYNCED/DUPLICATE 状态 + `idempotencyKey` 跨事件查询。蓝图 `edge_pending_event.idempotency_key UNIQUE` 的方向被代码证实正确。
- **新增待决**：相同 idempotency_key、不同 payload 当前 Demo 未实现 CONFLICT 判定。DB 阶段建议增加请求载荷哈希列（如 `payload_hash`），命中同 key 不同 hash 时返回 409；请在 open questions 中补充该项。
- 补传稳定排序已固化为 `edge_occurred_at NULLS LAST, event_id`，索引设计应覆盖 `(edge_node_id, status, edge_occurred_at, event_id)`；可重放状态集合为 PENDING/FAILED。
- HTTP Idempotency-Key（Kvrocks/IdempotencyService）与业务补传幂等仍是两套机制，`sys_idempotency_record` 的原有讨论不变。

## 三、业务编号：Generator 替换点（PBF-03）

- 四类编号已抽接口：ALM-yyyyMMdd-NNN、AI-E-yyyyMMdd-NNN（上海自然日）、RULE-<域前缀>-NNN（PER/DEV/AI/ALM/LNK/NTF）、FENCE-NNN。
- DB 阶段替换 Demo 实现时不需要改 Service；建议 openGauss 侧采用"日序列/号段 + 业务编号 UNIQUE"，主键仍按蓝图使用 UUID/BIGINT 与业务编号分离。EDGE-EVT-* 为 Simulator 时间戳序号，不进编号体系。

## 四、事务边界与事件发布（PBF-04）

- 未来 `@Transactional` 候选方法已逐一定位（见 Phase B 文档第 7 节边界表，9 行）：AI confirm/assign、Alert 处置命令、Personnel 越界、Collision 建单/升级、Rule/Fence publish、Edge replayOne、Edge 恢复。
- 代码已保证"全部 save 完成后才广播 LiveEvent"（LiveEventGate）。DB 阶段把 gate flush 替换为 `afterCommit` / Outbox 即可，**outbox_event 表是否采用的决策维持 OPEN**，本阶段不新增论据强制采用；Demo 可继续 afterCommit 直连 WebSocket。
- Edge replay 的原子单元是**单条事件**（replayOne），replayPending 是循环；不要把整批恢复设计成一个大事务。

## 五、派生字段去留的代码侧印证（PBF-05，对齐 Q1~Q10）

Phase B copy 语义改造印证了架构审计 Q1~Q10 的结论，数据库设计修订时保持：

| 字段 | 结论 | 代码侧证据 |
|---|---|---|
| Alert.mobileStage / acceptedAt / arrivedAt（Q1） | mobileStage 为移动端子状态；acceptedAt/arrivedAt 是事实时间可落主表，mobileStage 可由 timeline 事件派生，建议主表存两个时间戳、mobileStage 作派生/轻量列 | Alert 聚合 timeline 已有 ACCEPTED/ARRIVED eventType + sequenceNo（Phase A） |
| CollisionDevice.latestAlertId（Q2） | **派生镜像，不做权威外键**；可从未关闭告警查询派生，如保留仅作缓存列、不加 FK | CollisionService 写 latestAlertId 后显式 save，但权威建单在 Alert 域 |
| Personnel.activeAlertIds（Q3） | **请求级投影，不落库** | InMemory copy 中该字段不随仓储保存，PersonnelService 在返回副本上 decorate |
| AI ↔ Alert 双向 linked（Q4） | 维持单向为主：ai_event.linked_alert_id 权威回写；alert.linked_ai_event_id 为可空溯源软引用、无 FK、不做双向状态同步 | confirm 工作流：建 Alert → 回写 AI linkedAlertId，同一边界内完成 |
| Rule/Fence EdgeSync（Q5） | 保持独立两套同步表，不合并 Config Bundle | RuleService/FenceService 各自 publish + 各自 sync 模拟 |
| RecoveryPhase（Q6） | 不建表；ops_event_log 为审计权威 | EdgeOpsService 恢复流程为运行态对象，仓储无对应实体 |
| Alert Linkage（Q7） | 现场联动权威记录在 Alert 聚合（linkage + steps 子表）；Collision 的 steps 仅为回显缓存 | CollisionService 联动结果经 AlertService 落入 Alert 聚合 |
| Analytics（Q8） | 完全保持 Projection，不建 analytics_* 业务表 | AnalyticsService 只读 AlertRepository 聚合；surge 演示数据经 DemoAlertMaintenance |
| Overview/Screen（Q9） | 重复投影逻辑未来抽共享 Query/Classifier，不建表 | 两服务均为只读聚合 |
| Repository 直接加 JDBC 实现（Q10） | **Phase B 后契约可行**：无 InMemory 类型泄漏、无 Demo 方法、无内部引用返回；剩余工作是聚合行映射与级联 | 9 仓储契约测试 + copy 语义测试 |

另：`slaRemainingSec`、`alertsToday`、`distanceToday` 等均为派生/演示统计字段，不做权威列（slaRemainingSec 由 slaDeadline 计算；distanceToday/alertsToday 属 Demo 运行统计，正式口径由时序/告警表聚合）。

## 六、Demo 维护能力不进入 JDBC 实现（PBF-06）

- `resetDemoData / clearDemoData / DemoAlertMaintenance` 仅 InMemory 实现与 Demo/Test 装配使用；**JDBC Repository 不得实现清空/物理删除接口**。种子迁移走独立 migration/seed 方案（蓝图 Phase 1 主数据、Phase 2 业务种子的计划不变）。
- 真实 Alert 不物理删除（无 deleteById 契约）；User/Rule/Fence 为 disable 软删除策略，蓝图方向不变。
- Ops 日志 append-only（代码契约已只剩 append/recent），蓝图 ops_event_log 不需要 update/delete 路径。

## 七、查询契约对索引设计的印证（PBF-07）

- AlertQuery 已在仓储侧真实生效的过滤维度：status、risk、area、eventType、source、assignee、keyword、from/to、timeRange、分页。组合索引候选维持蓝图：`(status, risk, area, occurred_at)`、`(assignee_user_id, occurred_at)`、dedupKey 查询 `(dedup_key, status)`。
- AI：`(status, area, occurred_at)`、`(camera_id, occurred_at)`；Rule：`(status, category)`；Edge pending：见 PBF-02；Ops log：`(edge_node_id, occurred_at)`。
- 主数据（Team/Area/User）与小规模 Demo 列表服务仍用 findAll，不需要为此预建复杂查询接口。

## 八、明确不在本次 findings 中变更的内容

- 表数量（29 张草案）、表名、字段类型、JSONB 边界、openGauss/openGemini/Kvrocks/RocketMQ/文件存储职责划分，维持现有蓝图，Phase B 未产生推翻性证据。
- 状态码中文→机器 code 的落库映射（Phase A 已在代码层完成 code 权威、中文 label 兼容），DB 设计修订时按 code 列 + 字典/标签映射统一处理，属既定方向。
- 不新增任何表来承载 LiveEventGate、Demo Generator、DemoFeatureGuard——它们是应用层机制，不是持久化实体。
