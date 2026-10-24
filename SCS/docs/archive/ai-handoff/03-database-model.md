# 03 - openGauss 数据库模型与领域对象映射

> **数据库定位**：华为国产关系型数据库 **openGauss 6.x**（基于 PostgreSQL 线协议，驱动使用 `org.opengauss:opengauss-jdbc:6.0.0`）  
> **Schema 空间**：`safety`  
> **权威 DDL 脚本**：`backend/src/main/resources/db/design/openGauss-schema-final-draft.sql`  
> **状态评定**：DDL 最终版已就绪（READY FOR COMPATIBILITY SPIKE），当前尚未在真实 openGauss 服务器上执行建表。

---

## 1. Schema 物理设计总体指标

依据对 `openGauss-schema-final-draft.sql` 的真实解析核对，数据库对象统计如下：
- **物理活动表（Core Active Tables）**：**25 张**（核心业务表 24 张 + 平台发号表 1 张 `sys_business_number`）。
- **物理自增序列（Sequences）**：**21 个**，全部通过 `BIGINT NOT NULL DEFAULT nextval('safety.seq_xxx')` 显式 1:1 绑定到对应主键列。
- **UUID 聚合根主键表**：**3 张**（`safety_alert`、`ai_event`、`edge_pending_event`），主键使用 `VARCHAR(36)` 由应用层生成 UUIDv4/v7，支撑边缘离线自治与多实例无锁插入。
- **延期物理表（Deferred）**：5 张（字典、HTTP 幂等、附件文件、Outbox 事件），当前仅保留规格，不入第一期活动 DDL。
- **时序数据红线**：高频人员轨迹（`track_point`）与连续毫米波测距（`distance_point`）**严格禁止落入 openGauss**，统一规划至时序数据库 openGemini。

---

## 2. 全量物理表清单与结构矩阵

| 序号 | 物理表名 (`safety.*`) | 所属业务域 | 核心职责 | 主键 (PK) | 序列绑定 (nextval) | 业务唯一键 (Business Key / UNIQUE) | 外键关联 (FK) |
| :-: | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | `sys_team` | 主数据 | 货场作业班组台账 | `id BIGINT` | `seq_sys_team` | `team_code UNIQUE` | 无 |
| 2 | `safety_area` | 主数据 | 货场作业区域与股道划分 | `id BIGINT` | `seq_safety_area` | `area_code UNIQUE` | `parent_id -> safety_area(id)` |
| 3 | `sys_user` | 主数据 | 作业人员与责任人主数据 | `id BIGINT` | `seq_sys_user` | `user_code UNIQUE` | `team_id -> sys_team(id)` |
| 4 | `safety_personnel` | 人员定位 | 人员实时状态与手环绑定 | `id BIGINT` | `seq_safety_personnel` | `personnel_code UNIQUE` | `user_id -> sys_user(id)`, `team_id -> sys_team(id)` |
| 5 | `device_camera` | AI 视觉 | 监控摄像头与边缘接入台账 | `id BIGINT` | `seq_device_camera` | `camera_code UNIQUE` | `area_id -> safety_area(id)` |
| 6 | `collision_device`| 防碰撞 | 龙门吊/翻箱机防碰撞台账 | `id BIGINT` | `seq_collision_device` | `device_code UNIQUE` | 无 (解除 active_alert_id 强外键) |
| 7 | `edge_node` | 边缘运维 | EDGE-01~04 边缘计算网关 | `id BIGINT` | `seq_edge_node` | `node_code UNIQUE` | 无 |
| 8 | `safety_alert` | 告警主链 | **告警聚合根**：处置状态与等级 | `id VARCHAR(36)` | UUID | `business_no UNIQUE` (`ALM-*`) | `rule_id`, `camera_id` |
| 9 | `safety_alert_timeline` | 告警主链 | 告警全生命周期时间线日志 | `id BIGINT` | `seq_safety_alert_timeline` | 无 (复合索引: `alert_id, sequence_no`) | `alert_id -> safety_alert(id)` |
| 10| `safety_alert_evidence` | 告警主链 | 告警不可篡改证据结构 (JSONB) | `id BIGINT` | `seq_safety_alert_evidence` | `UNIQUE(alert_id, evidence_type)` | `alert_id -> safety_alert(id)` |
| 11| `safety_alert_treatment`| 告警主链 | 处置措施、记录与结果附件 | `id BIGINT` | `seq_safety_alert_treatment` | 无 | `alert_id -> safety_alert(id)` |
| 12| `safety_alert_linkage` | 告警主链 | 现场安全联动主单与模式 | `id BIGINT` | `seq_safety_alert_linkage` | `UNIQUE(alert_id)` | `alert_id -> safety_alert(id)` |
| 13| `safety_alert_linkage_step`| 告警主链 | 联动 7 步执行回执 (含 PLC) | `id BIGINT` | `seq_safety_alert_linkage_step` | `UNIQUE(linkage_id, step_id)` | `linkage_id -> safety_alert_linkage(id)` |
| 14| `ai_event` | AI 视觉 | **AI 事件聚合根**：抓拍与复核 | `id VARCHAR(36)` | UUID | `business_no UNIQUE` (`AI-E-*`) | `camera_id`, `linked_alert_id` |
| 15| `ai_event_timeline` | AI 视觉 | AI 复核时间线流转节点 | `id BIGINT` | `seq_ai_event_timeline` | 无 | `ai_event_id -> ai_event(id)` |
| 16| `safety_fence` | 电子围栏 | **围栏聚合根**：基础元数据 | `id BIGINT` | `seq_safety_fence` | `fence_code UNIQUE` (`FENCE-*`) | `area_id -> safety_area(id)` |
| 17| `safety_fence_version` | 电子围栏 | 围栏版本快照与 Polygon(JSONB) | `id BIGINT` | `seq_safety_fence_version` | `UNIQUE(fence_id, version_no)` | `fence_id -> safety_fence(id)` |
| 18| `safety_fence_edge_sync`| 电子围栏 | 围栏下发边缘节点同步状态表 | `id BIGINT` | `seq_safety_fence_edge_sync` | `UNIQUE(fence_id, edge_node_id)` | `fence_id`, `edge_node_id` |
| 19| `safety_rule` | 规则引擎 | **规则聚合根**：元数据与当前版本 | `id BIGINT` | `seq_safety_rule` | `rule_code UNIQUE` (`RULE-*`) | 无 |
| 20| `safety_rule_area` | 规则引擎 | 规则适用区域关联表 | `id BIGINT` | `seq_safety_rule_area` | `UNIQUE(rule_id, area_id)` | `rule_id`, `area_id` |
| 21| `safety_rule_version` | 规则引擎 | 规则版本快照与参数配置(JSONB) | `id BIGINT` | `seq_safety_rule_version` | `UNIQUE(rule_id, version_no)` | `rule_id -> safety_rule(id)` |
| 22| `safety_rule_edge_sync`| 规则引擎 | 规则下发边缘节点同步状态表 | `id BIGINT` | `seq_safety_rule_edge_sync` | `UNIQUE(rule_id, edge_node_id)` | `rule_id`, `edge_node_id` |
| 23| `edge_pending_event` | 边缘运维 | 边缘离线缓存队列与重放状态 | `id VARCHAR(36)` | UUID | `UNIQUE(edge_node_id, offline_event_id)` | `edge_node_id`, `linked_alert_id` |
| 24| `ops_event_log` | 边缘运维 | 边缘运维与补传审计日志 (Append) | `id BIGINT` | `seq_ops_event_log` | 无 (按 `occurred_at DESC` 检索) | `edge_node_id -> edge_node(id)` |
| 25| `sys_business_number` | 平台发号 | **分布式单调发号表** (行排他锁) | `business_type VARCHAR(32)` | 无 (单行主键) | 主键即业务类型 | 无 |

---

## 3. 序列与主键解耦设计原则

在 openGauss DDL 架构中，实现了**底层物理递增主键与上层业务单号（Business Number）的 100% 解耦**：

1. **底层物理主键**：
   - 绝大多数关系表均采用 `BIGINT NOT NULL DEFAULT nextval('safety.seq_xxx')`，利用 openGauss 数据库原生序列保证高并发插入时的无锁递增与极高性能；
   - 应用层在 `INSERT` 时不传 `id` 列，由驱动通过 JDBC `Statement.RETURN_GENERATED_KEYS` 获取自增主键。
2. **业务可见编号**：
   - 用户与前台看到的单号（如 `ALM-20260921-001`、`RULE-PER-001`、`FENCE-001`）存储在各表的 `business_no` / `fence_code` / `rule_code` 列，并施加 `UNIQUE` 唯一索引约束；
   - 业务单号通过表 `safety.sys_business_number` 进行分布式安全发号（详见第 04 与 06 篇），物理 ID 的跳号、回滚不会影响业务单号的连续性。

---

## 4. 领域对象 (Domain) ↔ 数据库表 (DB) ↔ 仓储 (Repository) 映射矩阵

| 领域模型 (Java Class) | 数据库主表 + 级联子表 | 现有仓储接口 | 持久化方式转换方案 |
|---|---|---|---|
| `DemoAlert` | `safety_alert` (主)<br>+ `safety_alert_timeline`<br>+ `safety_alert_evidence`<br>+ `safety_alert_treatment`<br>+ `safety_alert_linkage`<br>+ `safety_alert_linkage_step` | `AlertRepository` | **级联聚合落库**：主表保存状态/SLA；证据转入 evidence 表 (JSONB)；时间线转入 timeline 表；联动与步骤存 linkage 表。 |
| `DemoAiEvent` | `ai_event` (主)<br>+ `ai_event_timeline` | `AiEventRepository` | 主表存抓拍与复核状态、目标检测框；时间线级联入 timeline 表。 |
| `DemoFence` | `safety_fence` (主)<br>+ `safety_fence_version`<br>+ `safety_fence_edge_sync` | `FenceRepository` | 多边形顶点数组 `List<FencePoint>` 以 JSONB 格式存入 version 表的 `polygon_geojson` 列。 |
| `DemoRule` | `safety_rule` (主)<br>+ `safety_rule_version`<br>+ `safety_rule_area`<br>+ `safety_rule_edge_sync` | `RuleRepository` | 规则阈值参数 `List<RuleParam>` 以 JSONB 存入 version 表；区域与边缘节点状态存关联表。 |
| `DemoPersonnel` | `safety_personnel` | `PersonnelRepository` | 单表持久化人员基础信息与手环；连续轨迹点剥离入 openGemini。 |
| `DemoCollisionDevice` | `collision_device` | `CollisionRepository` | 单表持久化防碰撞设备台账与配对定义；PairState 为纯运行态不入库。 |
| `DemoEdgeNode` | `edge_node` | `EdgeNodeRepository` | 单表持久化边缘网关网络、心跳与当前同步版本。 |
| `EdgePendingEvent` | `edge_pending_event` | `EdgeEventQueueRepository` | 单表持久化离线补传队列；本地联动动作存入 `local_linkage_actions` (TEXT[])。 |
| `OpsEventLog` | `ops_event_log` | `OpsEventLogRepository` | 单表 Append-only 写入运维日志。 |

---

## 5. 关键字段与类型不一致差异审查（落库防坑指南）

在源码与 DDL 核对中，识别出以下在编写 JDBC Repository 时必须严格处理的字段映射细节：

1. **时区与时间格式**：
   - 领域模型中：部分展示字段使用 `HH:mm:ss` 字符串（如 `alert.time`），时间戳使用 `OffsetDateTime`（UTC+8）。
   - openGauss 中：所有时间列一律采用 `TIMESTAMPTZ`（带时区时间戳）。JDBC 写入时使用 `OffsetDateTime`，读取时派生展示格式。
2. **复合/嵌套对象**：
   - `DemoAlert.treatment`：为内嵌对象。在 DB 中对应独立的 `safety_alert_treatment` 1:1 子表；
   - `DemoFence.polygon`：为 `List<FencePoint>`。在 DB 中对应 `safety_fence_version.polygon_geojson`（openGauss `JSONB` 类型，驱动需映射为 String 或 PGobject）；
   - `DemoRule.versions`：为版本快照列表。在 DB 中对应 `safety_rule_version` 1:N 子表。
3. **已彻底消除的冗余字段（严禁再建表字段）**：
   - `collision_device.active_alert_id`：**已被正式 DDL 彻底删除**，严禁在 `collision_device` 表中增加此前缀；
   - `safety_fence.active_version`：新创建的草稿围栏允许其为 `NULL`，必须允许为空。
