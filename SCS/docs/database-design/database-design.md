# 安全卡控系统（SCS）数据库统一设计蓝图（openGauss 6.x）

> **文档定位**：系统从内存模型演进至 openGauss 6.x 物理持久化的**最高纲领性技术蓝图**。  
> **设计依据**：完整吸收 Phase A（语义规范化）、Phase B（仓储契约收敛）与 Phase B.5（B5-01~B5-05 关键缺陷修复）的代码事实。  
> **当前状态**：**READY FOR OPENGAUSS COMPATIBILITY SPIKE**（设计文档与 DDL 已完全收口；尚未连接真实数据库，尚未执行任何 SQL）。  
> **配套 DDL**：
> - [`../../backend/src/main/resources/db/design/openGauss-schema-final-draft.sql`](../../backend/src/main/resources/db/design/openGauss-schema-final-draft.sql) — **Final DDL 草案**（活动表 24 张 + 辅助表 1 张，共 25 张 `CREATE TABLE`，21 个 Sequence 全部显式绑定）。
> - [`../../backend/src/main/resources/db/design/openGauss-schema-draft.sql`](../../backend/src/main/resources/db/design/openGauss-schema-draft.sql) — *历史初稿（已打上 SUPERSEDED 废弃保留归档）*。

---

## 1. 总体设计原则

1. **业务语义代码化（Phase A 核心输入）**：
   - 彻底废除中文标签作为物理存储列与业务判断键，状态与风险一律采用稳定英文字符串 Code（如 `NORMAL`, `WARNING`, `SEVERE`, `URGENT`；`PENDING_CONFIRM` ~ `CLOSED`）。
   - 人员与责任人关系全面规范化为账号编码（`USR-*`）关联，历史姓名仅作为只读快照冗余（`*_name_snapshot`）。
2. **聚合根完整保存语义（Phase B 核心输入）**：
   - 确立“聚合根级事务保存（Aggregate Root Save）”契约，Service 层仅通过聚合根仓储操作子表；仓储层在单事务内保证主表与子表的原子级联一致性。
   - 仓储返回完全隔离的深拷贝对象（Deep-Copy），杜绝内存上下文污染，模拟未来 JDBC 事务边界。
3. **Phase B.5 核心代码事实固化（最高优先级输入）**：
   - **B5-01**：告警升级（Escalation）是风险等级的跃迁（`risk_level_code` 升级），主状态 `status_code` 严格保持不变，主状态集合彻底移除 `ESCALATED`；
   - **B5-02**：防碰撞 `activeAlertId` 仅为运行态缓存镜像，且单设备可同时参与多个 Pair（1:N），物理 DDL 彻底删除该列；去重权威收归 `safety_alert (dedup_key, status_code)`；
   - **B5-03**：事件发布通过事务提交后钩子（`afterCommit`）触发，应用层网关无持久化实体；
   - **B5-04**：编辑 `ACTIVE` 规则不降级主表状态，主表维持线上生效版本，未发布规则允许 `active_version = NULL`，新草稿存入版本子表；
   - **B5-05**：离线补传队列采用 `idempotency_key UNIQUE` + `payload_hash` 识别 409 冲突；业务编号由 `sys_business_number` 排他行锁控制。
4. **存储分工红线**：
   - 严格分离关系事实数据与高频时序数据，禁止将高频时序指标塞回 openGauss 主库。

---

## 2. 存储介质分工矩阵

| 存储介质 | 承载数据形态 | 明确禁止存放的内容 |
|---|---|---|
| **openGauss 6.x (关系主库)** | 主数据、资产台账、业务卡控规则、电子围栏版本、告警处置主链、AI 识别事件、离线补传队列、运维审计流水 | 高频采样点、原始流媒体二进制、秒级设备心跳序列 |
| **openGemini** | 高频时序数据：人员连续历史轨迹、防碰撞相对测距/速度时间序列、边缘 CPU/内存/网络秒级监控指标 | 业务事实源、卡控配置、审计事实、关系查询 |
| **Kvrocks (Redis 协议)** | 短期 HTTP 接口幂等缓存（TTL 600s）、热点态势缓存、会话 token | 长期持久化业务事实源（重启允许失效） |
| **RocketMQ** | 生产多实例 WebSocket 广播 fan-out、跨服务领域事件流、边缘上下行可靠通道 | 状态存储、直接查询 |
| **对象存储 (MinIO/S3)** | 现场抓拍照片、AI 检测图、违规视频、处置附件二进制内容 | 结构化元数据（元数据由数据库存 URL/Key） |

---

## 3. 表分类与一期活动表清单

数据库模型彻底摒弃旧草案的“29 张全量无差别建表”，重新按生产准备度划分为 **Active Phase 1 Core（24 张）**、**Platform Auxiliary（1 张）** 与 **Deferred Tables（5 张）**：

```
                    ┌──────────────────────────────────────────────┐
                    │          openGauss 6.x 物理数据库            │
                    └──────────────────────┬───────────────────────┘
                                           │
         ┌─────────────────────────────────┴─────────────────────────────────┐
         ▼                                                                   ▼
┌─────────────────────────────────┐                       ┌─────────────────────────────────────┐
│   Active Phase 1 Core (24 张)   │                       │       Deferred Tables (延期实现)    │
├─────────────────────────────────┤                       ├─────────────────────────────────────┤
│ 1. sys_team (班组台账)           │                       │ 1. sys_dictionary (动态字典主表)    │
│ 2. safety_area (作业区域台账)    │                       │ 2. sys_dictionary_item (字典项)     │
│ 3. sys_user (系统责任人)        │                       │ 3. sys_idempotency_record (HTTP幂等)│
│ 4. safety_personnel (现场人员)  │                       │ 4. safety_attachment (统一文件附件) │
│ 5. device_camera (摄像头台账)   │                       │ 5. outbox_event (跨实例MQ发件箱)   │
│ 6. collision_device (防碰撞设备) │                       └─────────────────────────────────────┘
│ 7. edge_node (边缘节点台账)     │                                          ▲
│ 8. safety_alert (告警主表)       │                                          │ 严格禁止回流
│ 9. safety_alert_timeline (时间线)│                       ┌──────────────────┴──────────────────┐
│ 10. safety_alert_evidence (证据) │                       │       openGemini 时序数据库         │
│ 11. safety_alert_treatment (处置)│                       ├─────────────────────────────────────┤
│ 12. safety_alert_linkage (联动)  │                       │ * safety_personnel_track (高频轨迹) │
│ 13. safety_alert_linkage_step    │                       │ * collision_measurement (高频测距)  │
│ 14. ai_event (AI识别事件)        │                       │ * edge_node_metric (边缘秒级指标)   │
│ 15. ai_event_timeline (AI时间线) │                       └─────────────────────────────────────┘
│ 16. safety_fence (围栏主表)      │
│ 17. safety_fence_version (版本)  │
│ 18. safety_fence_edge_sync (同步)│
│ 19. safety_rule (规则主表)       │
│ 20. safety_rule_area (适用区域)  │
│ 21. safety_rule_version (版本)   │
│ 22. safety_rule_edge_sync (同步) │
│ 23. edge_pending_event (离线队列)│
│ 24. ops_event_log (运维日志)     │
├─────────────────────────────────┤
│   Platform Auxiliary (1 张)     │
├─────────────────────────────────┤
│ 25. sys_business_number (发号器) │
└─────────────────────────────────┘
```

* **总计建表语句数**：24 张核心业务表 + 1 张平台发号辅助表 = **25 `CREATE TABLE`**。
* **物理主键序列**：**21 个独立 Sequence** 全部显式绑定到 21 张 BIGINT PK 表，无孤立序列。

---

## 4. 主数据与责任人策略

1. **组织与用户分离（USR-* vs P-*）**：
   - `sys_user`（USR-*）：登录系统、派单审批、现场复核、安全员、调度员；关联班组 `team_code`，角色 Code 化；
   - `safety_personnel`（P-*）：现场作业佩戴手环、受电子围栏监控、被定位的人员；工号 `job_no`，手环编号 `bracelet_code`。二者同名（如“李娜”）不代表同一实体，通过可空的 `linked_user_code` 软关联。
2. **区域与班组层级保留**：
   - `safety_area`：包含箱区通道、铁路装卸线等 12 个区域，未确认层级的区域 `parent_id` 保持 NULL，`demo_unresolved = TRUE`；
   - `sys_team`：外协单位、质量管理作为独立分类保留。

---

## 5. 核心业务领域事件模型设计

### 5.1 告警主链（Alert Aggregate）—— B5-01 核心修复落地
- **主状态机彻底移除 ESCALATED**：
  主状态 `status_code` 仅包含 8 态：
  `PENDING_CONFIRM` → `CONFIRMED` → `PENDING_ASSIGNMENT` → `PENDING_PROCESS` → `PROCESSING` → `PENDING_REVIEW` → `CLOSED`，作废状态 `CANCELLED`。
- **风险等级正交化**：
  升级操作仅跃迁 `risk_level_code`（如 WARNING/SEVERE → URGENT），主状态 `status_code` 维持原状（如 PROCESSING）；新增 `previous_risk_level_code` 记录升级前风险等级，废除旧设计中错误的 `upgraded_from_alert_no`。
- **决策来源（Decision Provenance）**：
  新增 `decision_source_type`（FENCE, RULE, AI_MODEL, COLLISION, EDGE, MANUAL）、`decision_source_code`、`decision_source_version`。当来源为 FENCE 时记录围栏编号与版本，`rule_code` 保持 NULL。

### 5.2 AI 识别事件（AI Aggregate）
- 独立于 Alert 主表：负责画面识别、置信度、检测框、复核及派单入口；
- 独立风险词表：`risk_code` 采用三级（`LOW`, `MEDIUM`, `HIGH`），不与告警四级混淆；
- 跨聚合权威回写：确认违规后生成 Alert，回写 `ai_event.linked_alert_id = alert.id`；Alert 侧的 `linked_ai_event_id` 仅为溯源软引用，杜绝双向强外键死锁。

### 5.3 防碰撞设备（Collision）—— B5-02 & Patch 4 核心修复落地
- **拆分风险与传感器健康**：
  `risk_level_code`（SAFE, WARNING, SEVERE, URGENT）与 `sensor_health_code`（NORMAL, UNCERTAIN, RADAR_DOWN）正交；雷达断数 `UNCERTAIN` 属于健康态，禁止作为风险等级建单。
- **彻底删除 active_alert_id / latest_alert_id**：
  一台设备可同时与多个相邻设备发生交会碰撞（如 VEH-07 同时与 TIP-02、CRANE-01 靠近），设备表上的单一列存在 1:N 基数冲突；
  当前开放碰撞告警的查询完全收拢至 `safety_alert`（依据未闭环的 `dedup_key` 如 `COLLISION:VEH-07:TIP-02`）。

### 5.4 电子围栏（Fence Aggregate）
- 主表 `safety_fence` 维护当前全局状态与生效版本指针 `active_version`（未发布新围栏为 NULL）；
- 子表 `safety_fence_version` 冻结多边形顶点 `polygon` (JSONB) 与管控班组 `team_scope` (JSONB)，保留未确认的“检修班”原始文本，不强行建立主数据外键；
- 同步表 `safety_fence_edge_sync` 维护 EDGE-01~04 的版本对账状态。

### 5.5 安全规则配置（Rule Aggregate）—— B5-04 & Patch 3 核心修复落地
- **主表状态保持（ACTIVE 保护）**：
  编辑线上正在运行的已生效规则时，主表 `safety_rule.status_code` 严格保持 `ACTIVE`，`active_version` 维持线上运行版本（如 v2.4），`effective_at` 不被抹除，边缘节点继续运行原版本；
- **版本可空与草稿版本沉淀**：
  未发布的新建规则 `active_version` 为 `NULL`，`platform_version` 为 `v1.0`；编辑时在 `safety_rule_version` 插入一条新草稿版本（`status_code = 'DRAFT'`，`version_no = 'v2.5'`），更新主表 `platform_version = 'v2.5'`；
- **发布时切换主指针**：
  `publish` 事务中将新版本置为 `ACTIVE`、旧版本置为 `ARCHIVED`、更新主表 `active_version = 'v2.5'` 与 `effective_at`，下发边缘节点同步。

### 5.6 边缘离线补传队列（Edge Pending Queue）—— Patch 5 语义统一
- `edge_pending_event` 建立 `idempotency_key UNIQUE` 约束；
- 新增 `payload_hash VARCHAR(64)` 列，支持对同 Key 异 Payload 请求返回 409 `IDEMPOTENCY_CONFLICT`；
- 合法业务事件 `edge_occurred_at TIMESTAMPTZ NOT NULL` 必填；
- 稳定重放排序索引统一为：`(edge_node_code, status_code, edge_occurred_at ASC, event_id ASC)`，消除冗余 `NULLS LAST`；
- 明确：测试辅助标记 `failNextReplay` 严禁落库。

---

## 6. 主键与业务编号策略

| 表类型 | 数据库物理主键 | 业务对外唯一编号 | 并发生成与保障机制 |
|---|---|---|---|
| **主数据 / 资产** (`sys_user`, `collision_device` 等) | `BIGINT DEFAULT nextval(...)` (21 个独立 Sequence 显式绑定) | `user_code`, `device_code` UNIQUE | 管理端低频新建，由独立序列保证物理自增 |
| **事件主表** (`safety_alert`, `ai_event`, `edge_pending_event`) | **应用层 UUID** | `alert_no`, `event_no` UNIQUE | 边缘端离线安全、多实例无协调；业务单号由 `sys_business_number` 排他行锁控制 |
| **子表明细** (`timeline`, `evidence`, `treatment`, `version`) | `BIGINT DEFAULT nextval(...)` (独立 Sequence) | 联合键 `(parent_id, sequence_no / version_no)` UNIQUE | 聚合根级联插入，单调保序 |

---

## 7. 事务边界与事件发布（Transaction Boundaries）

```
[用户/API 请求] 
       │
       ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 顶层 Service 编排方法 (@Transactional(rollbackFor = Exception.class)) │
│                                                                        │
│   1. 校验前置业务状态机                                               │
│   2. 写入/更新 聚合 A (Repository.save)                                │
│   3. 写入/更新 聚合 B (Repository.save)                                │
│   4. 注册事务提交后回调 (TransactionSynchronization.afterCommit)      │
│                                                                        │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
                     事务提交成功 (Commit)
                                   │
                                   ▼
                   [触发 WebSocket / Outbox 广播]
```

- **原子事务单元清单**：
  1. `AiService.confirm()`：AI 状态更新 + Alert 聚合插入 + linked_alert_id 回写 + 两侧 timeline 追加（单事务强一致）。
  2. `AlertService.treatment()`：Alert 主表更新 + treatment 记录 + timeline 节点追加。
  3. `AlertService.escalate()`：Alert 主表 risk_level 升级 + timeline 节点追加。
  4. `RuleService.publish()`：Rule 主表指针切换 + version 子表状态更新 + edge_sync 表批量插入。
  5. `FenceService.publish()`：Fence 主表指针切换 + version 子表状态更新 + edge_sync 表批量插入。
  6. `EdgeReplayService.replayOne()`：**单条事件独立事务**，处理成功更新状态并建 Alert，失败不影响队列其余事件。

---

## 8. 数据库迁移与演进路线图

```
[Step 0: Compatibility Spike] ──> 在 openGauss 6.x 验证驱动、Sequence、UUID、TIMESTAMPTZ、部分索引
       │
[Step 1: 物理 Schema 创建] ────> 执行 openGauss-schema-final-draft.sql 创建 safety schema 及 24+1 张表
       │
[Step 2: 主数据迁移 (P0)] ─────> 编写 sys_team, safety_area, sys_user 的 JDBC Repository，验证单事务
       │
[Step 3: 核心告警聚合迁移 (P0)] ──> 编写 AlertRepository JDBC 级联存取，验证五型证据与时间线
       │
[Step 4: AI 识别聚合迁移 (P0)] ──> 编写 AiEventRepository JDBC，打通 AI confirm 跨聚合原子事务
       │
[Step 5: 资产与控制迁移 (P1)] ───> 落地 personnel, collision, camera, edge_node, fence, rule
       │
[Step 6: 边缘离线队列落地 (P1)] ──> 落地 edge_pending_event 补传事务与 ops_event_log 审计
       │
[Step 7: 统计投影 SQL 调优] ────> 将 Analytics / Overview 从内存聚合改写为数据库高效 SQL 聚合
       │
[Step 8: 生产多实例扩展] ───────> 评估开启 outbox_event 并对接 RocketMQ
```

**红线提示**：严禁将最复杂的告警主链（Step 3）作为第一批迁移对象；必须先通过最简单的主数据表（Step 2）走通连接池、事务与数据源装配。
