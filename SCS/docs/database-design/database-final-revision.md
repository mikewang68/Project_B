# 数据库设计最终修订报告（Phase A + Phase B + Phase B.5 → openGauss Final Revision）

> 阶段目标：根据 Phase A、Phase B 及 Phase B.5 最终代码事实，对数据库设计做最后一次统一修订，生成可用于下一阶段 openGauss Compatibility Spike 的 Final DDL Draft。  
> 状态：**READY FOR COMPATIBILITY SPIKE**（设计已收口，未连接数据库，未执行任何 SQL）。
> 权威 DDL：[`../../backend/src/main/resources/db/design/openGauss-schema-final-draft.sql`](../../backend/src/main/resources/db/design/openGauss-schema-final-draft.sql)。

---

## 一、输入与演进事实汇总

本轮修订严格遵循代码事实优先级：`当前实际代码 > Phase B.5 修复文档 > Phase A/B 修复文档 > 架构审计 findings > 旧数据库设计草案`。

### 1. Phase A 输入（业务语义与 Code 化硬化）
- **状态与风险全面 Code 化**：废除中文字面量作为持久化与业务判断键，统一使用稳定英文字符串 Code（`NORMAL`, `WARNING`, `SEVERE`, `URGENT` 等）。
- **责任人关系正式化**：责任人采用 `assignee_user_code`（USR-*）关联，姓名仅作为冻结快照 `assignee_name_snapshot`。
- **决策来源血缘（Decision Provenance）引入**：增加 `decision_source_type`（FENCE, RULE, AI_MODEL, COLLISION, EDGE, MANUAL）、`decision_source_code`、`decision_source_version`。
- **时间线结构化**：废弃根据文本 `message LIKE '%到场%'` 反推事件类型，采用 `AlertTimelineEventTypes` 与 `AiTimelineEventTypes` 机器事件 Code，并按 `sequence_no` 强制保序。
- **时空事实时间**：事实时间统一使用 `TIMESTAMPTZ`，废除展示型时间字符串建列。

### 2. Phase B 输入（仓储契约与事务边界硬化）
- **聚合保存契约（Aggregate Root Save）**：确立 `AlertRepository.save` 为主表及全部子表明细（timeline, evidence, treatment, linkage, linkage_step）的级联事务保存；Service 不再直接操作子表仓储。
- **边云补传幂等权威迁移**：删除内存 JVM HashSet，以 `edge_pending_event.idempotency_key UNIQUE` 与队列仓储状态作为判重唯一权威。
- **业务编号解耦**：从业务 Service 抽离四类编号 Generator（Alert, AI, Rule, Fence）。
- **Demo 维护接口隔离**：`resetDemoData / clearDemoData / DemoAlertMaintenance` 明确为 Demo / InMemory 专用，**零数据库映射**，禁止进入 JDBC 仓储契约。

### 3. Phase B.5 输入（最高优先级最终代码事实）
- **B5-01 (Alert 升级语义正交化)**：升级是风险等级提升（`risk_level_code` 跃迁），主状态 `status_code` 严格保持不变，彻底废除 `ESCALATED` 主状态枚举；增加 `previous_risk_level_code` 记录升级历史，废除旧设计中错误的 `upgraded_from_alert_no`。
- **B5-02 (Collision 关联弱化与权威归属)**：`collision_device.active_alert_id` 仅为运行态缓存镜像，且单设备可同时参与多个 Pair，存在 1:N 基数冲突，物理 DDL 彻底删除该列；去重与状态判断以 `safety_alert (dedup_key, status_code)` 未关闭告警为唯一权威。
- **B5-03 (事务与事件发布收口)**：顶层跨聚合工作流原子保存后，由 `afterCommit` 统一触发外部事件广播；应用层发布门无持久化实体。
- **B5-04 (ACTIVE 规则编辑版本保护)**：编辑生效中规则不改变主表状态与生效时间，主表 `active_version` 保持线上运行版本，未发布规则允许为 `NULL`；在 `safety_rule_version` 子表新增草稿版本（`DRAFT`），边缘节点继续运行原版本，直至发布时切换主指针。
- **B5-05 (业务编号安全与离线冲突判定)**：单 JVM 内存扫描探测解决了 Demo 间隙冲突；正式多实例单调唯一编号由 `sys_business_number` 排他行锁接管，业务编号列设 `UNIQUE` 索引；离线补传队列增加 `payload_hash` 列识别 409 载荷冲突。

---

## 二、架构决策与表分类调整（SQL 文本扫描核准）

不再机械保留旧草案的“29 张全量建表”，基于真实业务写路径与架构分工进行严格分级：

### 1. Active Phase 1 Core（核心业务活动表，共 24 张）
准备在 openGauss 第一期落地的正式业务表：
- **组织与区域主数据 (3)**：`sys_team`、`safety_area`、`sys_user`
- **现场人员与设备资产 (4)**：`safety_personnel`、`device_camera`、`collision_device`、`edge_node`
- **告警处置主聚合簇 (6)**：`safety_alert`、`safety_alert_timeline`、`safety_alert_evidence`、`safety_alert_treatment`、`safety_alert_linkage`、`safety_alert_linkage_step`
- **AI 识别事件聚合簇 (2)**：`ai_event`、`ai_event_timeline`
- **电子围栏聚合簇 (3)**：`safety_fence`、`safety_fence_version`、`safety_fence_edge_sync`
- **安全规则配置聚合簇 (4)**：`safety_rule`、`safety_rule_area`、`safety_rule_version`、`safety_rule_edge_sync`
- **边缘离线队列与运维日志 (2)**：`edge_pending_event`、`ops_event_log`

### 2. Platform Auxiliary Table（平台发号辅助表，1 张）
- **`safety.sys_business_number`**：多实例排他行锁计数表，用于安全派发 `ALM-yyyyMMdd-NNN` 等按日循环业务编号。
- **总活动建表语句数**：24 张 Core 表 + 1 张 Auxiliary 表 = **25 张 `CREATE TABLE`**。

### 3. Deferred Tables（延期表，不进一期活动 DDL，仅以注释保留，共 5 张）
- **`sys_dictionary` / `sys_dictionary_item`**：状态码与枚举已在 Java 枚举和模型中固化，前端有完整 Label Mapper，一期无任何后台动态字典增删业务写路径，拒绝建立空壳表。
- **`sys_idempotency_record`**：当前接口防重由 Kvrocks 原生 TTL (600s) 提供毫秒级拦截，无超过 10 分钟跨度的持久化审计需求。
- **`safety_attachment`**：当前尚未接入正式对象存储服务（MinIO/S3），现场照片/截图仍为模拟路径，待文件存储技术栈明确后再标准化落地。
- **`outbox_event`**：单实例 JDBC 演进阶段由 Spring `TransactionSynchronization.afterCommit` 钩子直发 WebSocket，仅在生产引入 RocketMQ 时启用。

### 4. Prohibited in openGauss（严格禁止回流的主库边界，共 3 类）
- **`safety_personnel_track`**（人员高频轨迹）、**`collision_measurement`**（毫米波高频测距）、**`edge_node_metric`**（边缘秒级监控指标）属于典型高频时序写入负载，**统一由 openGemini 时序数据库承载**，严禁塞回 openGauss 关系库！

### 5. Projection No-Table（明确不建表的 30+ 视图/聚合对象）
- Overview / SafetyScreen / Analytics / Mobile / metrics / KPI 等全部基于核心表进行 SQL 动态聚合，不建物理表。

---

## 三、主键生成与 Sequence 显式绑定策略

为避免物理 DDL 中出现孤立 Sequence，所有 21 个物理 `BIGINT` 主键表均在表前声明对应独立序列，并在表主键列上显式绑定 `DEFAULT nextval(...)`：

| 序号 | 序列名 (`Sequence`) | 对应物理表 (`Table`) | 绑定列 (`Column`) | 主键生成策略 (`Strategy`) | 实机验证标记 (`VERIFY`) |
| :-: | :--- | :--- | :--- | :--- | :--- |
| 1 | `seq_sys_team` | `safety.sys_team` | `id` | `DEFAULT nextval('safety.seq_sys_team')` | VERIFY ON OPENGAUSS |
| 2 | `seq_safety_area` | `safety.safety_area` | `id` | `DEFAULT nextval('safety.seq_safety_area')` | VERIFY ON OPENGAUSS |
| 3 | `seq_sys_user` | `safety.sys_user` | `id` | `DEFAULT nextval('safety.seq_sys_user')` | VERIFY ON OPENGAUSS |
| 4 | `seq_safety_personnel` | `safety.safety_personnel` | `id` | `DEFAULT nextval('safety.seq_safety_personnel')` | VERIFY ON OPENGAUSS |
| 5 | `seq_device_camera` | `safety.device_camera` | `id` | `DEFAULT nextval('safety.seq_device_camera')` | VERIFY ON OPENGAUSS |
| 6 | `seq_collision_device` | `safety.collision_device` | `id` | `DEFAULT nextval('safety.seq_collision_device')` | VERIFY ON OPENGAUSS |
| 7 | `seq_edge_node` | `safety.edge_node` | `id` | `DEFAULT nextval('safety.seq_edge_node')` | VERIFY ON OPENGAUSS |
| 8 | `seq_safety_alert_timeline` | `safety.safety_alert_timeline` | `id` | `DEFAULT nextval('safety.seq_safety_alert_timeline')` | VERIFY ON OPENGAUSS |
| 9 | `seq_safety_alert_evidence` | `safety.safety_alert_evidence` | `id` | `DEFAULT nextval('safety.seq_safety_alert_evidence')` | VERIFY ON OPENGAUSS |
| 10 | `seq_safety_alert_treatment` | `safety.safety_alert_treatment` | `id` | `DEFAULT nextval('safety.seq_safety_alert_treatment')` | VERIFY ON OPENGAUSS |
| 11 | `seq_safety_alert_linkage` | `safety.safety_alert_linkage` | `id` | `DEFAULT nextval('safety.seq_safety_alert_linkage')` | VERIFY ON OPENGAUSS |
| 12 | `seq_safety_alert_linkage_step`| `safety.safety_alert_linkage_step`| `id` | `DEFAULT nextval('safety.seq_safety_alert_linkage_step')`| VERIFY ON OPENGAUSS |
| 13 | `seq_ai_event_timeline` | `safety.ai_event_timeline` | `id` | `DEFAULT nextval('safety.seq_ai_event_timeline')` | VERIFY ON OPENGAUSS |
| 14 | `seq_safety_fence` | `safety.safety_fence` | `id` | `DEFAULT nextval('safety.seq_safety_fence')` | VERIFY ON OPENGAUSS |
| 15 | `seq_safety_fence_version` | `safety.safety_fence_version` | `id` | `DEFAULT nextval('safety.seq_safety_fence_version')` | VERIFY ON OPENGAUSS |
| 16 | `seq_safety_fence_edge_sync` | `safety.safety_fence_edge_sync` | `id` | `DEFAULT nextval('safety.seq_safety_fence_edge_sync')` | VERIFY ON OPENGAUSS |
| 17 | `seq_safety_rule` | `safety.safety_rule` | `id` | `DEFAULT nextval('safety.seq_safety_rule')` | VERIFY ON OPENGAUSS |
| 18 | `seq_safety_rule_area` | `safety.safety_rule_area` | `id` | `DEFAULT nextval('safety.seq_safety_rule_area')` | VERIFY ON OPENGAUSS |
| 19 | `seq_safety_rule_version` | `safety.safety_rule_version` | `id` | `DEFAULT nextval('safety.seq_safety_rule_version')` | VERIFY ON OPENGAUSS |
| 20 | `seq_safety_rule_edge_sync` | `safety.safety_rule_edge_sync` | `id` | `DEFAULT nextval('safety.seq_safety_rule_edge_sync')` | VERIFY ON OPENGAUSS |
| 21 | `seq_ops_event_log` | `safety.ops_event_log` | `id` | `DEFAULT nextval('safety.seq_ops_event_log')` | VERIFY ON OPENGAUSS |

* **Sequence 统计**：创建总数 = 21；表主键消费绑定数 = 21；孤立序列 = 0。
* **UUID 主键表（3 张）**：`safety_alert`、`ai_event`、`edge_pending_event` 采用应用层生成 UUID，保证断网离线自治生成与多实例并发不冲突。
* **复合主键表（1 张）**：`sys_business_number` 采用 `(number_type, business_date)` 复合主键。

---

## 四、重大字段变更与命名规范

| 实体 / 领域 | 旧草案设计（已废除） | 最终修订设计（Final Authority） | 变更理由与代码证据 |
|---|---|---|---|
| **Alert 风险** | `risk_level`（中文/混杂） | `risk_level_code VARCHAR(16)` | 使用 `NORMAL/WARNING/SEVERE/URGENT` 机器 Code |
| **Alert 主状态** | 包含 `ESCALATED` 状态 | 仅 8 态（无 `ESCALATED`） | B5-01: 升级是风险等级变化，主状态维持不变 |
| **Alert 升级溯源** | `upgraded_from_alert_no` (FK) | `previous_risk_level_code VARCHAR(16)` | 消除误解：记录原风险等级，不是源告警 ID |
| **责任人关联** | `assignee VARCHAR(64)` (姓名) | `assignee_user_code` + `assignee_name_snapshot` | 机器 Code 正式关联，姓名仅作历史快照（F-02） |
| **决策血缘** | 缺失 / 伪造 `rule_id` | `decision_source_type/code/version` | 人员越界基于围栏版本，不伪造 `RULE-PER-001`（F-07） |
| **规则外键** | 强制非空 `rule_id` | `rule_code VARCHAR(48)`（允许 NULL） | 消除代理键歧义，且非规则源告警无需强求规则编码 |
| **规则版本** | `active_version NOT NULL` | `active_version VARCHAR(24) NULL` | B5-04: 未发布草稿规则允许为 NULL；编辑时不降级 |
| **碰撞关联** | `active_alert_id` / `latest_alert_id` | **彻底删除该列**（0 持久列） | B5-02 / Patch 4: 运行态缓存且单设备多 Pair 存在 1:N 基数冲突，去重归 Alert |
| **人员综合状态** | 单一混杂字段 | 拆分 `online_status_code` / `bracelet_status_code` / `person_risk_code` | 彻底解耦在岗状态、终端状态与业务风险（F-01） |
| **碰撞风险健康** | 混入“待确认”健康态 | 拆分 `risk_level_code` 与 `sensor_health_code` | `UNCERTAIN` 归属雷达健康态，不作为碰撞风险等级 |
| **补传幂等** | 仅 `idempotency_key` | 增加 `payload_hash VARCHAR(64)` | 支持同 key 同 payload 判重，同 key 异 payload 报 409 |
| **补传发生时标** | 混入可空与 NULLS LAST 冲突 | `edge_occurred_at TIMESTAMPTZ NOT NULL` | 合法事件必填发生时间，索引统一为 ASC 保序 |
| **时间字段** | 混入 `time VARCHAR(8)` 字符串 | 统一 `TIMESTAMPTZ`（发生/接收/补传分立） | 消除展示型字符串建列，事实时间带时区存储 |

---

## 五、核心索引与外键策略

### 1. 核心索引设计（紧扣 Repository Query 契约）
- **Alert 列表与分页**：`(status_code, risk_level_code, area_code, occurred_at DESC)`，覆盖 `AlertQuery` 组合过滤。
- **Alert 责任人待办**：`(assignee_user_code, status_code, occurred_at DESC)`。
- **Alert 开放去重（B5-02 核心索引）**：
  ```sql
  CREATE INDEX idx_safety_alert_dedup_open ON safety.safety_alert (dedup_key, status_code)
      WHERE status_code <> 'CLOSED' AND status_code <> 'CANCELLED';
  ```
  *(VERIFY ON OPENGAUSS: 部分索引实测；回退方案为普通复合索引 + 事务层过滤)*。
- **AI 事件列表检索**：`(status_code, risk_code, area_code, occurred_at DESC)`、`(camera_code, occurred_at DESC)`。
- **Edge 离线补传保序（稳定重放）**：
  ```sql
  CREATE INDEX idx_edge_pending_replay_order ON safety.edge_pending_event (
      edge_node_code, status_code, edge_occurred_at ASC, event_id ASC
  );
  ```

### 2. 外键分层策略
- **Strong FK（库级强外键）**：仅用于聚合根与其内部专属子表（同生命周期级联）：
  - `safety_alert` → `timeline`, `evidence`, `treatment`, `linkage` (+ `step`)
  - `ai_event` → `timeline`
  - `safety_fence` → `version`, `edge_sync`
  - `safety_rule` → `area`, `version`, `edge_sync`
- **Soft FK（软引用无物理外键）**：跨聚合关系仅存 `_code` 或 `_id` 业务标识，禁止加库级强约束（如 `collision_device` 与 `safety_alert`，`sys_user` 与各事件）。

---

## 六、openGauss Compatibility Spike 输入清单

进入真实环境后的 24 项实机验证清单：
1. `CREATE SCHEMA safety` 权限与 search_path 搜索路径行为。
2. 21 个独立 Sequence 创建语法（`START WITH 1000 INCREMENT BY 1`）及在高并发调用下的性能。
3. `DEFAULT nextval('safety.seq_xxx')` 绑定与插入时自增主键自动回填。
4. Sequence 回滚行为实测（跳号不影响业务）。
5. 高并发 `nextval()` 单调递增性与号段性能。
6. `id BIGINT` 在 JDBC 执行 `RETURNING id` 或 `getGeneratedKeys` 的驱动兼容性。
7. `TIMESTAMPTZ` 时区写入转换（Asia/Shanghai ↔ UTC）。
8. Java 8+ `OffsetDateTime` / `Instant` 与 openGauss 驱动映射。
9. `JSONB` 多态证据载荷的存储与检索性能。
10. `JSONB` 路径索引与 `GIN` 索引支持度。
11. 部分索引（`Partial Index`，`WHERE status_code <> 'CLOSED'`）在查询执行计划中的生效判定。
12. 复合索引执行计划覆盖分析。
13. `INSERT ... ON CONFLICT (...) DO UPDATE` 语法兼容性。
14. `MERGE INTO` 语法实机支持对比。
15. 业务发号器 `SELECT ... FOR UPDATE` 排他行锁高并发测试。
16. CHECK 约束在非法枚举值插入时的拦截行为。
17. 库级强外键级联删除（`ON DELETE CASCADE`）在聚合根删除时的性能影响。
18. `UNIQUE` 业务唯一键冲突时抛出的 SQLState 及异常转译规范。
19. 本地数据库事务 commit / rollback 一致性。
20. Spring Boot 3.5.5 HikariCP 与 `org.opengauss.Driver` 官方驱动连接池稳定性。
21. 通用驱动 `org.postgresql.Driver` 在 openGauss 6.x 上的探针表现对比。
22. 批量插入（Batch Insert）在边缘离线补传场景下的吞吐实测。
23. `sys_business_number` 跨天日期切换时的排他插入与发号行为。
24. Schema 级用户权限隔离规范。

---

## 七、验证与交付

- **全量测试回归**：后端 205 个测试全部通过（100% Green），前端 typecheck 0 错误（100% Green）；
- **交付权威 DDL**：[`../../backend/src/main/resources/db/design/openGauss-schema-final-draft.sql`](../../backend/src/main/resources/db/design/openGauss-schema-final-draft.sql)；
- **状态结论**：**READY FOR OPENGAUSS COMPATIBILITY SPIKE**。
