# 数据库数据字典（openGauss 6.x 最终设计修订版）

> 配套 DDL：[`../../backend/src/main/resources/db/design/openGauss-schema-final-draft.sql`](../../backend/src/main/resources/db/design/openGauss-schema-final-draft.sql)（FINAL DESIGN DRAFT，未在数据库执行）。
> 目标数据库：openGauss 6.x / PostgreSQL 线协议；时间类型统一 `TIMESTAMPTZ`；schema 前缀统一为 `safety.`。
> 架构演进依据：吸收 Phase A（机器 Code 化）、Phase B（聚合根级持久化契约）与 Phase B.5（B5-01~B5-05 代码事实）。

---

## 0. 设计规范与约定

| 规范项 | 约束与约定说明 |
| :--- | :--- |
| **物理主键** | 业务主数据/配置/子表明细采用 `BIGINT` + `SEQUENCE`；事件/告警/离线队列采用 `UUID`（应用层生成）。 |
| **业务唯一键** | 实体业务主键均命名为 `*_code` 或 `*_no`，必须施加 `UNIQUE` 索引/约束。 |
| **审计追溯** | 配置与台账表：`created_at`, `updated_at`, `is_deleted`, `lock_version`；事件流表：`occurred_at`, `created_at`。 |
| **人员与责任人** | 严禁以中文字符串作为业务主外键；统一使用系统账号 `assignee_user_code` / `operator_user_code` + `*_name_snapshot` 快照。 |
| **状态与风险** | 彻底废除中文标签作为物理存储列。状态字段统一为 `status_code`，风险字段统一为 `risk_level_code` / `risk_code`。 |
| **半结构化** | 仅多态证据快照、版本多边形/参数、离线联动等扩展数据允许使用 `JSONB`；核心关系维度一律平铺建列。 |
| **时序边界红线** | 高频连续轨迹、高频防碰撞毫米波测距、每秒边缘指标等时序写入严格划归 **openGemini**，禁止建在 openGauss 中。 |

---

## 一、主数据与组织区域（Master Data，全部 P0）

### 1. 班组主数据 `sys_team`
- **业务定位**：作业班组主数据台账（Demo 6 行）。
- **来源 Model**：`DemoMasterData.DemoTeam(code, name)`

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_sys_team`） |
| `team_code` | `VARCHAR(48)` | N | | UQ | 班组业务编码：`LOADING_TEAM_1/2`, `SAFETY_MANAGEMENT` 等 |
| `team_name` | `VARCHAR(64)` | N | | | 班组中文全称（装卸一班、安全管理组等） |
| `team_type` | `VARCHAR(24)` | N | `'INTERNAL'` | | 班组类型：`INTERNAL` / `CONTRACTOR` / `QUALITY` |
| `enabled` | `BOOLEAN` | N | `TRUE` | | 启用状态（停用不物理删除） |
| `demo_unverified` | `BOOLEAN` | N | `FALSE` | | Demo 数据未核验标记 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录创建时间 |
| `updated_at` | `TIMESTAMPTZ` | Y | | | 记录最后更新时间 |

### 2. 作业区域主数据 `safety_area`
- **业务定位**：货场作业区域规范台账（Demo 12 行）。
- **来源 Model**：`DemoMasterData.DemoArea`

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_safety_area`） |
| `area_code` | `VARCHAR(48)` | N | | UQ | 区域规范编码：`LOADING_AREA_A/B`, `GANTRY_CRANE_AREA` 等 |
| `area_name` | `VARCHAR(64)` | N | | | 区域规范中文名称 |
| `area_type` | `VARCHAR(32)` | N | `'OPERATION'` | | 区域类型：`OPERATION` / `LANE` / `BLOCK` / `FACILITY` |
| `parent_id` | `BIGINT` | Y | | FK→self | 父级区域物理外键（**UNRESOLVED 期间一律 NULL**） |
| `enabled` | `BOOLEAN` | N | `TRUE` | | 启用状态 |
| `demo_unresolved` | `BOOLEAN` | N | `FALSE` | | 箱区 A/B、机房等未确认层级标记 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录创建时间 |
| `updated_at` | `TIMESTAMPTZ` | Y | | | 记录更新时间 |

### 3. 系统用户与责任人 `sys_user`
- **业务定位**：安全卡控系统责任人台账（供派单、审批、复核软引用，不存密码/Token）。
- **来源 Model**：`DemoMasterData.DemoUser` + `DemoUserProperties`

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_sys_user`） |
| `user_code` | `VARCHAR(32)` | N | | UQ | 用户唯一编号：`USR-001` ~ `USR-007` |
| `user_name` | `VARCHAR(64)` | N | | | 责任人真实姓名快照 |
| `team_code` | `VARCHAR(48)` | Y | | 软引用 | 所属班组软引用（外部 IAM 场景可空） |
| `role_code` | `VARCHAR(48)` | Y | | | 角色机器 Code：`SAFETY_OFFICER`, `DISPATCHER` 等 |
| `role_name_snapshot` | `VARCHAR(64)` | Y | | | 角色中文名快照 |
| `shift_code` | `VARCHAR(24)` | Y | | | 班次编码：`DAY_SHIFT`, `NIGHT_SHIFT` |
| `enabled` | `BOOLEAN` | N | `TRUE` | | 启用状态 |
| `demo_unverified` | `BOOLEAN` | N | `FALSE` | | Demo 未核验账号标记（USR-005/006/007） |
| `is_deleted` | `BOOLEAN` | N | `FALSE` | | 逻辑软删除标记 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录创建时间 |
| `updated_at` | `TIMESTAMPTZ` | Y | | | 记录更新时间 |

---

## 二、现场人员与设备资产（Field Personnel & Devices）

### 4. 现场作业人员 `safety_personnel`（P0）
- **业务定位**：现场作业人员最新态势与终端佩戴台账。
- **来源 Model**：`DemoPersonnel`
- **核心约定**：明确在岗状态（`online_status_code`）、手环状态（`bracelet_status_code`）、业务风险（`person_risk_code`）三维正交；`person_state`、`distance_today`、`alerts_today` 均为前端或查询派生字段，不建物理持久列。高频连续轨迹归 openGemini。

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_safety_personnel`） |
| `personnel_code` | `VARCHAR(32)` | N | | UQ | 作业人员编码：`P-ZHAO`, `P-002` 等 |
| `job_no` | `VARCHAR(32)` | Y | | UQ | 现场工号：`P-24018`（正式）、`V-10012`（外协） |
| `person_name` | `VARCHAR(64)` | N | | | 人员真实姓名 |
| `team_code` | `VARCHAR(48)` | Y | | 软引用 | 所属班组规范编码 |
| `area_code` | `VARCHAR(48)` | Y | | 软引用 | 当前所在区域编码 |
| `bracelet_code` | `VARCHAR(32)` | Y | | | 佩戴的手环硬件编码（如 `WB-018`） |
| `battery_pct` | `SMALLINT` | Y | | | 手环当前电量百分比（0~100） |
| `online_status_code` | `VARCHAR(16)` | N | `'ONLINE'` | | 在岗状态：`ONLINE` / `OFFLINE` |
| `bracelet_status_code` | `VARCHAR(24)` | N | `'ONLINE'` | | 手环终端状态：`ONLINE` / `OFFLINE` / `LOW_BATTERY` |
| `person_risk_code` | `VARCHAR(16)` | N | `'NORMAL'` | | 业务风险感知：`NORMAL` / `ATTENTION` / `HIGH` |
| `positioning_quality` | `VARCHAR(24)` | Y | | | 定位信号感知值：`EXCELLENT` / `GOOD` / `POOR` / `NO_SIGNAL` |
| `pos_x` | `NUMERIC(8,3)` | Y | | | 画布相对百分比坐标 X（0.000 ~ 100.000） |
| `pos_y` | `NUMERIC(8,3)` | Y | | | 画布相对百分比坐标 Y（0.000 ~ 100.000） |
| `coordinate_text` | `VARCHAR(64)` | Y | | | 坐标展示文本快照 |
| `linked_user_code` | `VARCHAR(32)` | Y | | 软引用 | 预留关联的系统责任人账号（无强外键） |
| `last_seen_at` | `TIMESTAMPTZ` | Y | | | 最后位置或心跳上报时间 |
| `enabled` | `BOOLEAN` | N | `TRUE` | | 是否在册启用 |
| `is_deleted` | `BOOLEAN` | N | `FALSE` | | 逻辑删除 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录创建时间 |
| `updated_at` | `TIMESTAMPTZ` | Y | | | 记录更新时间 |

### 5. 摄像头台账 `device_camera`（P1）
- **业务定位**：监控摄像头台账与视觉分析绑定信息。
- **来源 Model**：`CameraInfo`

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_device_camera`） |
| `camera_code` | `VARCHAR(48)` | N | | UQ | 摄像头规范编号：`CAM-01` ~ `CAM-08` |
| `camera_name` | `VARCHAR(96)` | N | | | 摄像头安装点位名称 |
| `area_code` | `VARCHAR(48)` | Y | | 软引用 | 安装所属区域编码 |
| `rtsp_url` | `VARCHAR(256)` | Y | | | 视频流地址（禁止明文保存鉴权凭证） |
| `stream_type` | `VARCHAR(24)` | N | `'MAIN'` | | 码流类型：`MAIN` / `SUB` |
| `health_code` | `VARCHAR(24)` | N | `'NORMAL'` | | 设备健康态：`NORMAL` / `DEGRADED` / `OFFLINE` |
| `ptz_supported` | `BOOLEAN` | N | `FALSE` | | 是否支持云台变焦控制 |
| `ai_enabled` | `BOOLEAN` | N | `TRUE` | | 是否开启 AI 视觉检测 |
| `assigned_edge_code` | `VARCHAR(32)` | Y | | 软引用 | 负责推流分析的边缘节点编号 |
| `pos_x` | `NUMERIC(8,3)` | Y | | | 地图百分比坐标 X |
| `pos_y` | `NUMERIC(8,3)` | Y | | | 地图百分比坐标 Y |
| `installed_at` | `TIMESTAMPTZ` | Y | | | 设备安装上线时间 |
| `last_heartbeat_at` | `TIMESTAMPTZ` | Y | | | 最后视频流或状态心跳时间 |
| `is_deleted` | `BOOLEAN` | N | `FALSE` | | 逻辑删除标记 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录创建时间 |
| `updated_at` | `TIMESTAMPTZ` | Y | | | 记录更新时间 |

### 6. 防碰撞设备台账 `collision_device`（P1）
- **业务定位**：货场重点防碰撞受控设备（车辆、翻箱机、吊机）台账与运行态。
- **来源 Model**：`DemoCollisionDevice`
- **核心约定**：设备健康态（`sensor_health_code`）与业务风险等级（`risk_level_code`）正交拆分；彻底删除 `active_alert_id` / `latest_alert_id` 列，因单设备可同时参与多个 Pair（存在 1:N 基数冲突），去重与当前告警查询权威收拢至 `safety_alert (dedup_key, status_code)`。

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | `DEFAULT nextval('safety.seq_collision_device')` | PK | 物理代理键（序列 `seq_collision_device`） |
| `device_code` | `VARCHAR(48)` | N | | UQ | 设备业务编号：`VEH-07`, `TIP-02`, `CRANE-01` 等 |
| `device_name` | `VARCHAR(64)` | N | | | 设备显示名称 |
| `device_type` | `VARCHAR(32)` | N | | | 设备类别：转运车辆 / 翻箱机 / 龙门吊 |
| `area_code` | `VARCHAR(48)` | Y | | 软引用 | 当前作业区域编码 |
| `status_code` | `VARCHAR(24)` | N | `'NORMAL'` | | 设备运行状态：`NORMAL` / `RUNNING` / `STANDBY` / `FAULT` |
| `speed` | `NUMERIC(6,2)` | Y | | | 当前运行速度（m/s） |
| `direction` | `VARCHAR(32)` | Y | | | 行进方向（东/南/西/北/作业角度） |
| `control_status` | `VARCHAR(24)` | Y | | | 控制系统状态：`MANUAL` / `AUTO` / `REMOTE` / `INTERLOCKED` |
| `communication_status` | `VARCHAR(24)` | Y | | | 5G/专网通信链路状态 |
| `radar_status` | `VARCHAR(24)` | Y | | | 毫米波雷达运行状态 |
| `risk_level_code` | `VARCHAR(16)` | N | `'SAFE'` | | 碰撞风险等级：`SAFE` / `WARNING` / `SEVERE` / `URGENT` |
| `sensor_health_code` | `VARCHAR(24)` | N | `'NORMAL'` | | 传感器健康态：`NORMAL` / `UNCERTAIN` / `RADAR_DOWN` |
| `related_device_code` | `VARCHAR(48)` | Y | | | 最近配对碰撞目标设备编号 |
| `pos_x` | `NUMERIC(8,3)` | Y | | | 地图百分比坐标 X |
| `pos_y` | `NUMERIC(8,3)` | Y | | | 地图百分比坐标 Y |
| `last_updated_at` | `TIMESTAMPTZ` | Y | | | 态势最后刷新时间 |
| `is_deleted` | `BOOLEAN` | N | `FALSE` | | 逻辑删除标记 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录创建时间 |
| `updated_at` | `TIMESTAMPTZ` | Y | | | 记录更新时间 |

### 7. 边缘节点台账 `edge_node`（P1）
- **业务定位**：边缘计算计算节点台账与在线运行态。
- **来源 Model**：`DemoEdgeNode`

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_edge_node`） |
| `node_code` | `VARCHAR(32)` | N | | UQ | 节点编号：`EDGE-01` ~ `EDGE-04` |
| `node_name` | `VARCHAR(96)` | N | | | 边缘节点规范名称 |
| `area_code` | `VARCHAR(48)` | Y | | 软引用 | 部署覆盖作业区域 |
| `ip_address` | `VARCHAR(45)` | Y | | | 节点内网 IP 地址 |
| `node_status_code` | `VARCHAR(16)` | N | `'ONLINE'` | | 状态：`ONLINE` / `DEGRADED` / `OFFLINE` / `RECOVERING` / `ERROR` |
| `cloud_connected` | `BOOLEAN` | N | `TRUE` | | 是否已连接云端管控平台 |
| `autonomy_active` | `BOOLEAN` | N | `FALSE` | | 是否正处于离线自治工作状态 |
| `agent_version` | `VARCHAR(32)` | Y | | | 部署的 Edge Agent 版本 |
| `active_rule_version` | `VARCHAR(24)` | Y | | | 边缘实际生效的规则版本号 |
| `expected_rule_version` | `VARCHAR(24)` | Y | | | 云端期望该节点生效的规则版本号 |
| `active_fence_version` | `VARCHAR(24)` | Y | | | 边缘实际生效的围栏版本号 |
| `expected_fence_version` | `VARCHAR(24)` | Y | | | 云端期望该节点生效的围栏版本号 |
| `clock_offset_ms` | `BIGINT` | Y | | | 节点与云端时钟偏差（毫秒，阈值 1000ms） |
| `latency_ms` | `INT` | Y | | | 网络通信延迟（毫秒） |
| `cpu_usage_pct` | `SMALLINT` | Y | | | 最新 CPU 使用率百分比（高频指标归 openGemini） |
| `memory_usage_pct` | `SMALLINT` | Y | | | 最新内存使用率百分比 |
| `disk_usage_pct` | `SMALLINT` | Y | | | 最新磁盘使用率百分比 |
| `temperature_c` | `NUMERIC(5,2)` | Y | | | 最新机箱温度（摄氏度） |
| `queue_depth` | `INT` | N | `0` | | 离线缓存待补传事件当前排队深度 |
| `cached_event_count` | `INT` | N | `0` | | 累计缓存事件数 |
| `uptime_sec` | `BIGINT` | Y | | | 节点连续运行秒数 |
| `last_heartbeat_at` | `TIMESTAMPTZ` | Y | | | 节点最后心跳时间 |
| `last_sync_at` | `TIMESTAMPTZ` | Y | | | 最后配置或补传同步时间 |
| `last_error` | `TEXT` | Y | | | 最新异常错误堆栈摘要 |
| `is_deleted` | `BOOLEAN` | N | `FALSE` | | 逻辑删除标记 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录创建时间 |
| `updated_at` | `TIMESTAMPTZ` | Y | | | 记录更新时间 |

---

## 三、告警处置主聚合（Alert Aggregate Cluster，全部 P0）

### 8. 告警主表 `safety_alert`
- **业务定位**：全系统唯一处置主链实体（包含人员、碰撞、AI、设备与离线补传各类告警）。
- **来源 Model**：`DemoAlert`
- **Phase B.5 核心语义**：
  1. `status_code` 彻底移除 `ESCALATED`；告警升级是 `risk_level_code` 跃迁，主状态不改变。
  2. `previous_risk_level_code` 记录升级前风险等级机器 Code；废除旧 `upgraded_from_alert_no`。
  3. 增加决策来源血缘字段：`decision_source_type`、`decision_source_code`、`decision_source_version`。
  4. 责任人全面切换为 `assignee_user_code` + 姓名快照。
  5. `rule_code` 与 `rule_version` 允许为 NULL（人员越界/设备碰撞/AI直报无需伪造规则）。

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `UUID` | N | | PK | 应用层生成的全局唯一 UUID（离线/多实例兼容） |
| `alert_no` | `VARCHAR(40)` | N | | UQ | 业务单号：`ALM-yyyyMMdd-NNN`、`ALM-DEMO-RISK` |
| `title` | `VARCHAR(200)` | N | | | 告警标题 |
| `source` | `VARCHAR(32)` | Y | | | 业务来源：人员安全 / 设备防碰撞 / AI视觉检测 等 |
| `event_type` | `VARCHAR(48)` | Y | | IDX | 细分业务事件类型机器 Code |
| `risk_level_code` | `VARCHAR(16)` | N | | IDX | 风险等级：`NORMAL` / `WARNING` / `SEVERE` / `URGENT` |
| `status_code` | `VARCHAR(24)` | N | | IDX | 主处置状态：`PENDING_CONFIRM` ~ `CLOSED`（**无 ESCALATED**） |
| `previous_risk_level_code` | `VARCHAR(16)` | Y | | | **B5-01 升级前风险等级机器 Code** |
| `upgraded_from_code` | `VARCHAR(16)` | Y | | | 历史首次升级来源等级 |
| `mobile_stage_code` | `VARCHAR(24)` | Y | | | 移动端当前流转阶段缓存（`PENDING` / `ACCEPTED` / `ARRIVED` / `PROCESSING`） |
| `priority` | `VARCHAR(16)` | Y | | | 响应优先级（普通 / 紧急） |
| `area_code` | `VARCHAR(48)` | Y | | IDX | 发生区域软引用 |
| `area_name_snapshot` | `VARCHAR(64)` | Y | | | 发生时区域名称快照 |
| `target_object_type` | `VARCHAR(32)` | Y | | | 目标对象类别（PERSONNEL / DEVICE / FENCE） |
| `target_object_id` | `VARCHAR(48)` | Y | | | 目标对象业务编码（如 `P-ZHAO`、`VEH-07`） |
| `target_object_name_snapshot` | `VARCHAR(64)` | Y | | | 目标对象名称快照 |
| `assignee_user_code` | `VARCHAR(32)` | Y | | IDX | 当前被指派责任人系统账号（`USR-*`） |
| `assignee_name_snapshot` | `VARCHAR(64)` | Y | | | 责任人真实姓名快照 |
| `confirm_user_code` | `VARCHAR(32)` | Y | | | 确认人系统账号 |
| `confirm_user_name_snapshot` | `VARCHAR(64)` | Y | | | 确认人姓名快照 |
| `review_user_code` | `VARCHAR(32)` | Y | | | 复核人系统账号 |
| `review_user_name_snapshot` | `VARCHAR(64)` | Y | | | 复核人姓名快照 |
| `review_note` | `TEXT` | Y | | | 复核意见说明 |
| `decision_source_type` | `VARCHAR(32)` | Y | | | 决策来源类型：`FENCE` / `RULE` / `AI_MODEL` / `COLLISION` / `EDGE` / `MANUAL` |
| `decision_source_code` | `VARCHAR(64)` | Y | | | 决策来源实体编码（如 `FENCE-003`、`RULE-DEV-003`） |
| `decision_source_version` | `VARCHAR(32)` | Y | | | 决策来源生效版本号（如 `v1.2`） |
| `rule_code` | `VARCHAR(48)` | Y | | | 关联规则编号（仅规则触发时记录，允许为 NULL） |
| `rule_version` | `VARCHAR(24)` | Y | | | 关联规则版本（仅规则触发时记录，允许为 NULL） |
| `linked_ai_event_id` | `UUID` | Y | | 软引用 | AI 视觉来源事件软引用（无强外键） |
| `origin` | `VARCHAR(24)` | N | `'CLOUD'` | | 告警产生途径：`CLOUD` / `EDGE_REPLAY` |
| `edge_node_code` | `VARCHAR(32)` | Y | | | 边缘补传节点编号（仅边缘补传事件有效） |
| `offline_event_id` | `VARCHAR(64)` | Y | | | 边缘本地离线事件编号 |
| `rule_version_used` | `VARCHAR(24)` | Y | | | 边缘触发判定时生效的规则版本 |
| `offline_occurred` | `BOOLEAN` | Y | `FALSE` | | 是否在断网离线期间发生 |
| `sync_delay_sec` | `BIGINT` | Y | | | 断网补传延迟秒数 |
| `synced_at` | `TIMESTAMPTZ` | Y | | | 补传成功入库时间戳 |
| `dedup_key` | `VARCHAR(160)` | Y | | 部分唯一 IDX | 去重特征键（B5-02：未闭环时排他唯一） |
| `duration_sec` | `INT` | Y | | | 持续时间（秒） |
| `sla_limit_min` | `INT` | Y | | | SLA 处置时限（分钟） |
| `sla_deadline` | `TIMESTAMPTZ` | Y | | | SLA 处置截止时间戳 |
| `occurred_at` | `TIMESTAMPTZ` | N | | IDX | 事实发生时间戳（边缘事件冻结边缘时钟） |
| `detected_at` | `TIMESTAMPTZ` | Y | | | 算法/设备检测到告警的时间戳 |
| `server_received_at` | `TIMESTAMPTZ` | Y | | | 中心服务端接收到告警的时间戳 |
| `confirmed_at` | `TIMESTAMPTZ` | Y | | | 人工确认时间戳 |
| `accepted_at` | `TIMESTAMPTZ` | Y | | | 移动端接单时间戳 |
| `arrived_at` | `TIMESTAMPTZ` | Y | | | 移动端到场签到时间戳 |
| `closed_at` | `TIMESTAMPTZ` | Y | | | 处置闭环时间戳 |
| `linkage_available` | `BOOLEAN` | Y | `FALSE` | | 是否支持声光/控制联动 |
| `linkage_finished` | `BOOLEAN` | Y | `FALSE` | | 联动是否已全部顺利完成 |
| `linkage_failed` | `BOOLEAN` | Y | `FALSE` | | 联动是否发生故障或中断 |
| `takeover` | `BOOLEAN` | Y | `FALSE` | | 是否已被人工接管接替自动联动 |
| `lock_version` | `INT` | N | `0` | | 乐观锁版本号 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录创建时间 |
| `updated_at` | `TIMESTAMPTZ` | Y | | | 记录更新时间 |

### 9. 告警时间线 `safety_alert_timeline`
- **业务定位**：告警流转审计时间线（Append-only，由 `sequence_no` 严格保序）。
- **来源 Model**：`TimelineEvent`

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_safety_alert_timeline`） |
| `alert_id` | `UUID` | N | | FK→alert | 所属告警主表外键（级联删除） |
| `sequence_no` | `INT` | N | | UQ(联合) | 聚合内严格递增序号（1, 2, 3...） |
| `event_type` | `VARCHAR(48)` | N | | | 时间线动作类型机器 Code |
| `state_code` | `VARCHAR(16)` | N | `'done'` | | 节点状态：`done` / `active` |
| `status_after` | `VARCHAR(24)` | Y | | | 该动作执行后告警的主状态机器 Code |
| `operator_user_code` | `VARCHAR(32)` | Y | | | 操作人系统账号（`USR-*` 或 `SYSTEM`） |
| `operator_name_snapshot` | `VARCHAR(64)` | Y | | | 操作人姓名快照 |
| `message` | `TEXT` | Y | | | 节点文本日志或操作说明 |
| `metadata` | `JSONB` | Y | | | 附加结构化审计参数（驳回理由、转派说明等） |
| `trace_id` | `VARCHAR(64)` | Y | | | 请求全链路追踪 ID |
| `occurred_at` | `TIMESTAMPTZ` | N | | | 节点事实发生时间戳 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录入库时间 |

### 10. 告警多态证据快照 `safety_alert_evidence`
- **业务定位**：告警多态结构化证据快照（单表 + JSONB Payload 承载 5 类 Sealed 证据）。
- **来源 Model**：`AlertEvidence` sealed union (`PersonnelAlertEvidence`, `CollisionAlertEvidence`, `AiAlertEvidence`, `DeviceMetricAlertEvidence`, `SystemMetricAlertEvidence`)

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_safety_alert_evidence`） |
| `alert_id` | `UUID` | N | | FK→alert | 所属告警主表外键（级联删除） |
| `evidence_type` | `VARCHAR(32)` | N | | | 证据类别：`PERSONNEL` / `COLLISION` / `AI` / `METRIC` |
| `source_code` | `VARCHAR(64)` | Y | | | 证据来源设备或模型编码 |
| `occurred_at` | `TIMESTAMPTZ` | Y | | | 证据采样时标 |
| `payload` | `JSONB` | N | | | 对应证据类型的结构化载荷（坐标序列/雷达测距/识别框/指标项） |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录入库时间 |

### 11. 现场处置记录 `safety_alert_treatment`
- **业务定位**：现场处置执行事实记录（支持复核驳回后多次处置）。
- **来源 Model**：`TreatmentRecord`

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_safety_alert_treatment`） |
| `alert_id` | `UUID` | N | | FK→alert | 所属告警主表外键 |
| `measures` | `JSONB` | Y | | | 采取的整改与防范措施列表（字符串数组） |
| `summary` | `TEXT` | Y | | | 现场处置结论文字说明 |
| `operator_user_code` | `VARCHAR(32)` | Y | | | 处置提交人账号 Code |
| `operator_name_snapshot` | `VARCHAR(64)` | Y | | | 处置人真实姓名快照 |
| `attachments` | `JSONB` | Y | | | 附件元数据数组快照 |
| `submitted_at` | `TIMESTAMPTZ` | N | | | 处置事实提交时间戳 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录入库时间 |

### 12. 告警联动会话 `safety_alert_linkage`
- **业务定位**：现场声光报警与 PLC 控制联动主会话。
- **来源 Model**：`DemoAlert.linkage` 聚合

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_safety_alert_linkage`） |
| `alert_id` | `UUID` | N | | FK→alert | 所属告警主表外键（1:1 级联） |
| `status_code` | `VARCHAR(24)` | N | `'STARTED'` | | 联动主状态：`STARTED` / `FINISHED` / `FAILED` / `TAKEOVER` |
| `started_at` | `TIMESTAMPTZ` | N | | | 联动启动时间戳 |
| `finished_at` | `TIMESTAMPTZ` | Y | | | 联动执行完成时间戳 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录入库时间 |

### 13. 联动执行步骤 `safety_alert_linkage_step`
- **业务定位**：联动执行 6 步阶段状态（检测告警/声光提醒/通知司机/请求减速/PLC停机/人工接管）。
- **来源 Model**：`LinkageStep`

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_safety_alert_linkage_step`） |
| `linkage_id` | `BIGINT` | N | | FK→linkage | 所属联动会话主表外键 |
| `step_no` | `INT` | N | | UQ(联合) | 步骤次序（1~6） |
| `step_name` | `VARCHAR(64)` | N | | | 步骤规范名称 |
| `status_code` | `VARCHAR(24)` | N | | | 步骤状态：`PENDING` / `SUCCESS` / `FAILED` / `SKIPPED` |
| `action_text` | `VARCHAR(128)` | Y | | | 下发指令或具体动作内容摘要 |
| `executed_at` | `TIMESTAMPTZ` | Y | | | 步骤执行时间戳 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录入库时间 |

---

## 四、AI 视觉识别事件聚合（AI Aggregate Cluster，全部 P0）

### 14. AI 识别事件主表 `ai_event`
- **业务定位**：AI 视觉分析违规识别事件（独立于告警主表，支持复核与派单转化）。
- **来源 Model**：`DemoAiEvent`

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `UUID` | N | | PK | 应用层生成的全局唯一 UUID |
| `event_no` | `VARCHAR(40)` | N | | UQ | 业务单号：`AI-E-yyyyMMdd-NNN` |
| `event_type` | `VARCHAR(48)` | N | | IDX | 违规识别类型（未戴安全帽/闯入禁区等） |
| `camera_code` | `VARCHAR(48)` | Y | | IDX | 抓拍摄像头编码 |
| `camera_name_snapshot`| `VARCHAR(64)` | Y | | | 抓拍摄像头点位名称快照 |
| `area_code` | `VARCHAR(48)` | Y | | IDX | 发生作业区域编码 |
| `confidence` | `NUMERIC(5,4)` | Y | | | 识别置信度（0.0000 ~ 1.0000） |
| `duration_sec` | `NUMERIC(8,2)` | Y | | | 违规持续秒数 |
| `model_code` | `VARCHAR(64)` | Y | | | 推理模型代号与版本 |
| `threshold` | `NUMERIC(5,4)` | Y | | | 触发告警的阈值配置 |
| `status_code` | `VARCHAR(24)` | N | | IDX | 复核流转状态：`PENDING` ~ `CLOSED` |
| `risk_code` | `VARCHAR(16)` | N | | | AI 风险等级：`LOW` / `MEDIUM` / `HIGH`（独立词表） |
| `camera_health_code`| `VARCHAR(24)` | Y | | | 抓拍时摄像头健康态 |
| `scene_type` | `VARCHAR(32)` | Y | | | 场景分类：`helmet` / `intrusion` 等 |
| `detection_boxes` | `JSONB` | Y | | | 目标边界框快照数组 `[{label,x,y,w,h,prob}]` |
| `rule_code` | `VARCHAR(48)` | Y | | | 关联规则业务编码 |
| `related_person_code`| `VARCHAR(32)`| Y | | 软引用 | 关联作业人员编码 |
| `related_device_code`| `VARCHAR(48)`| Y | | 软引用 | 关联设备编码 |
| `judge_text` | `TEXT` | Y | | | AI 自诊断识别依据描述 |
| `reviewer_user_code` | `VARCHAR(32)` | Y | | | 复核人账号 Code |
| `reviewer_name_snapshot`| `VARCHAR(64)`| Y | | | 复核人姓名快照 |
| `review_time` | `TIMESTAMPTZ` | Y | | | 复核执行时间戳 |
| `false_reason` | `TEXT` | Y | | | 误报原因分类与文字说明 |
| `assignee_user_code` | `VARCHAR(32)` | Y | | | 派单直接责任人账号 Code |
| `assignee_name_snapshot`| `VARCHAR(64)`| Y | | | 派单责任人姓名快照 |
| `assignment_priority`| `VARCHAR(16)`| Y | | | 派单优先级 |
| `process_status_code`| `VARCHAR(24)`| Y | | | 派单现场处置状态 |
| `assignment_note` | `TEXT` | Y | | | 派单说明备注 |
| `linked_alert_id` | `UUID` | Y | | IDX | 确认生成安全告警后的 `safety_alert.id`（软引用） |
| `occurred_at` | `TIMESTAMPTZ` | N | | IDX | 视觉检测事实发生时间戳 |
| `updated_at` | `TIMESTAMPTZ` | Y | | | 更新时间 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录入库时间 |

### 15. AI 识别事件时间线 `ai_event_timeline`
- **业务定位**：AI 识别与复核流程审计时间线。
- **来源 Model**：`AiTimelineNode`

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_ai_event_timeline`） |
| `ai_event_id` | `UUID` | N | | FK→ai_event | 所属 AI 事件外键（级联删除） |
| `sequence_no` | `INT` | N | | UQ(联合) | 顺序编号（1, 2, 3...） |
| `event_type` | `VARCHAR(48)` | N | | | 事件动作类型机器 Code |
| `state_code` | `VARCHAR(16)` | N | `'done'` | | 节点状态：`done` / `active` |
| `operator_user_code`| `VARCHAR(32)`| Y | | | 操作人账号 Code |
| `operator_name_snapshot`| `VARCHAR(64)`| Y | | | 操作人姓名快照 |
| `message` | `TEXT` | Y | | | 节点日志文本 |
| `occurred_at` | `TIMESTAMPTZ` | N | | | 发生时间戳 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录入库时间 |

---

## 五、电子围栏聚合（Fence Aggregate Cluster，全部 P0/P1）

### 16. 电子围栏主表 `safety_fence`（P0）
- **业务定位**：电子围栏业务主表（记录当前状态与生效版本指针）。
- **来源 Model**：`DemoFence`

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_safety_fence`） |
| `fence_code` | `VARCHAR(48)` | N | | UQ | 业务编号：`FENCE-001` ~ `FENCE-004` |
| `fence_name` | `VARCHAR(64)` | N | | | 围栏业务名称 |
| `fence_kind` | `VARCHAR(32)` | Y | | | 围栏类别（危险区域 / 临时施工围栏 / 授权作业区） |
| `tone` | `VARCHAR(24)` | Y | | | 前端渲染主色调：`danger` / `warning` / `normal` |
| `area_code` | `VARCHAR(48)` | Y | | 软引用 | 所属作业区域编码 |
| `active_version` | `VARCHAR(24)` | Y | | | 当前线上生效版本号（如 `v1.2`） |
| `status_code` | `VARCHAR(24)` | N | | IDX | 围栏状态：`DRAFT` / `TO_REVIEW` / `EFFECTIVE` / `DISABLED` |
| `effective_at` | `TIMESTAMPTZ` | Y | | | 当前版本生效起始时间 |
| `expires_at` | `TIMESTAMPTZ` | Y | | | 临时围栏失效时间 |
| `approver_user_code` | `VARCHAR(32)` | Y | | | 审批人账号 Code |
| `approver_name_snapshot`| `VARCHAR(64)`| Y | | | 审批人姓名快照 |
| `lock_version` | `INT` | N | `0` | | 乐观锁版本号 |
| `is_deleted` | `BOOLEAN` | N | `FALSE` | | 逻辑删除标记 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 创建时间 |
| `updated_at` | `TIMESTAMPTZ` | Y | | | 更新时间 |

### 17. 电子围栏版本明细 `safety_fence_version`（P0）
- **业务定位**：围栏几何多边形与授权班组版本历史快照。
- **来源 Model**：`FenceVersion`、`FencePoint`、`team_scope`

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_safety_fence_version`） |
| `fence_id` | `BIGINT` | N | | FK→fence | 所属围栏主表外键 |
| `version_no` | `VARCHAR(24)` | N | | UQ(联合) | 版本号（如 `v1.0`, `v1.1`） |
| `risk_level_code` | `VARCHAR(16)` | N | | | 闯入该围栏触发的告警等级机器 Code |
| `status_code` | `VARCHAR(24)` | N | | | 该版本的状态（`DRAFT` / `ACTIVE` / `ARCHIVED`） |
| `polygon` | `JSONB` | N | | | 多边形顶点数组快照 `[{x,y}]` |
| `team_scope` | `JSONB` | Y | | | 授权/管控班组快照 `[{teamCode,rawLabel,unresolved}]` |
| `created_by_user_code`| `VARCHAR(32)`| Y | | | 版本设计人账号 Code |
| `created_by_name_snapshot`| `VARCHAR(64)`| Y | | | 设计人姓名快照 |
| `effective_at` | `TIMESTAMPTZ` | Y | | | 版本生效时间戳 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录创建时间 |

### 18. 围栏边缘节点同步 `safety_fence_edge_sync`（P1）
- **业务定位**：围栏版本向边缘各节点下发与对账状态。
- **来源 Model**：`fence.model.EdgeNode`

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_safety_fence_edge_sync`） |
| `fence_id` | `BIGINT` | N | | FK→fence | 所属围栏外键 |
| `edge_node_code` | `VARCHAR(32)` | N | | UQ(联合) | 目标边缘节点编号（`EDGE-01` ~ `EDGE-04`） |
| `expected_version` | `VARCHAR(24)` | N | | | 平台期望该节点生效的版本号 |
| `edge_version` | `VARCHAR(24)` | Y | | | 边缘实际上报生效的版本号 |
| `sync_status` | `VARCHAR(24)` | N | | | 同步状态：`synced` / `syncing` / `mismatch` / `offline` |
| `synced_at` | `TIMESTAMPTZ` | Y | | | 最终同步一致时间戳 |

---

## 六、安全规则配置聚合（Rule Aggregate Cluster，全部 P0/P1）

### 19. 安全规则主表 `safety_rule`（P0）
- **业务定位**：卡控规则业务主表（记录当前线上运行版本与主状态）。
- **来源 Model**：`DemoRule`
- **Phase B.5 核心语义**：B5-04 确立编辑 ACTIVE 规则不改变主表状态，主表保持 `ACTIVE`，并在版本子表中新增草稿版本。

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | `DEFAULT nextval('safety.seq_safety_rule')` | PK | 物理代理键（序列 `seq_safety_rule`） |
| `rule_code` | `VARCHAR(48)` | N | | UQ | 业务编号：`RULE-DEV-003`, `RULE-PER-001` 等 |
| `rule_name` | `VARCHAR(96)` | N | | | 规则规范名称 |
| `category` | `VARCHAR(32)` | N | | IDX | 规则业务分类：人员安全 / 设备安全 / 协同策略 |
| `rule_type` | `VARCHAR(32)` | Y | | | 规则细分类型 |
| `scope_desc` | `VARCHAR(128)` | Y | | | 适用范围简短说明 |
| `active_version` | `VARCHAR(24)` | Y | | | **当前线上实际运行版本号**（从未发布的新建规则为 NULL；编辑时不降级） |
| `platform_version` | `VARCHAR(24)` | Y | | | 平台最新编辑版本号（如初建草稿为 `v1.0`，在线编辑新草稿为 `v2.5`） |
| `risk_level_code` | `VARCHAR(16)` | N | | | 触发告警风险等级机器 Code |
| `status_code` | `VARCHAR(24)` | N | | IDX | 规则全局主状态：`DRAFT` / `ACTIVE` / `DISABLED` 等 |
| `high_risk` | `BOOLEAN` | N | `FALSE` | | 高危规则标记（审批需二次确认） |
| `owner_user_code` | `VARCHAR(32)` | Y | | | 规则负责人账号 Code |
| `owner_name_snapshot`| `VARCHAR(64)`| Y | | | 负责人姓名快照 |
| `approver_user_code` | `VARCHAR(32)`| Y | | | 审批人账号 Code |
| `approver_name_snapshot`| `VARCHAR(64)`| Y | | | 审批人姓名快照 |
| `effective_at` | `TIMESTAMPTZ` | Y | | | 当前生效版本的激活生效时间戳（编辑不抹除） |
| `description` | `TEXT` | Y | | | 规则逻辑详细描述说明 |
| `related_modules` | `JSONB` | Y | | | 关联业务模块数组 |
| `lock_version` | `INT` | N | `0` | | 乐观锁版本号 |
| `is_deleted` | `BOOLEAN` | N | `FALSE` | | 逻辑删除标记 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录创建时间 |
| `updated_at` | `TIMESTAMPTZ` | Y | | | 记录更新时间 |

### 20. 规则适用区域关联 `safety_rule_area`（P0）
- **业务定位**：规则适用作业区域多值关联表（核心查询主轴，关系表建模）。

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_safety_rule_area`） |
| `rule_id` | `BIGINT` | N | | FK→rule | 所属规则主表外键 |
| `area_code` | `VARCHAR(48)` | N | | UQ(联合) | 适用区域编码（含 `ALL` 通配编码） |

### 21. 安全规则版本历史明细 `safety_rule_version`（P0）
- **业务定位**：规则版本历史快照、阈值参数与动作定义。
- **来源 Model**：`DemoRule.Version`, `Param`

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_safety_rule_version`） |
| `rule_id` | `BIGINT` | N | | FK→rule | 所属规则主表外键 |
| `version_no` | `VARCHAR(24)` | N | | UQ(联合) | 版本号（如 `v1.0`, `v2.4`, `v2.5`） |
| `status_code` | `VARCHAR(24)` | N | | | 版本状态：`DRAFT` / `REVIEW` / `ACTIVE` / `ARCHIVED` |
| `params` | `JSONB` | N | | | 结构化参数阈值快照 `[{label,value,danger,hint}]` |
| `actions` | `JSONB` | N | | | 触发联动动作列表（字符串数组） |
| `source_version` | `VARCHAR(24)` | Y | | | 回滚/派生修订的来源版本号 |
| `version_note` | `TEXT` | Y | | | 版本变更说明文本 |
| `version_diffs` | `JSONB` | Y | | | 相对上一版本的差异摘要快照 |
| `author_user_code` | `VARCHAR(32)` | Y | | | 版本起草人账号 Code |
| `author_name_snapshot`| `VARCHAR(64)`| Y | | | 起草人姓名快照 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录创建时间 |

### 22. 规则边缘节点同步 `safety_rule_edge_sync`（P1）
- **业务定位**：安全规则边缘节点下发与同步对账表。
- **来源 Model**：`DemoRule.EdgeNode`

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_safety_rule_edge_sync`） |
| `rule_id` | `BIGINT` | N | | FK→rule | 所属规则主表外键 |
| `edge_node_code` | `VARCHAR(32)` | N | | UQ(联合) | 目标边缘节点编号 |
| `expected_version` | `VARCHAR(24)` | N | | | 云端期望该节点生效的规则版本号 |
| `edge_version` | `VARCHAR(24)` | Y | | | 边缘上报实际生效的规则版本号 |
| `sync_status` | `VARCHAR(24)` | N | | | 同步状态：`synced` / `syncing` / `mismatch` / `offline` |
| `synced_at` | `TIMESTAMPTZ` | Y | | | 最终同步一致时间戳 |

---

## 七、边缘离线队列与运维审计（Edge Queue & Ops Logs，全部 P1）

### 23. 边缘离线补传事件队列 `edge_pending_event`（P1）
- **业务定位**：边缘断网离线缓存事件补传队列（判重权威与原子重放源）。
- **来源 Model**：`EdgePendingEvent`
- **核心契约**：
  - `idempotency_key` 施加全局 `UNIQUE` 索引。
  - 新增 `payload_hash` 列，用于精准检测“同键不同负载”引起的 409 冲突。
  - 测试用 `failNextReplay` 为运行时 transient 变量，绝对禁止建列。

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `UUID` | N | | PK | 全局唯一 UUID |
| `event_id` | `VARCHAR(64)` | N | | UQ(联合) | 边缘本地事件唯一编号 |
| `edge_node_code` | `VARCHAR(32)` | N | | UQ(联合), IDX| 产生该事件的边缘节点编号 |
| `event_type` | `VARCHAR(48)` | N | | | 事件类型机器 Code |
| `business_key` | `VARCHAR(128)`| Y | | | 业务追踪组合键 |
| `edge_occurred_at` | `TIMESTAMPTZ` | N | | 排序IDX | 边缘端发生时钟（补传建单取该时间，严格冻结） |
| `received_at` | `TIMESTAMPTZ` | N | | | 边缘本地入缓存时间 |
| `server_received_at`| `TIMESTAMPTZ`| Y | | | 中心服务端接收时间 |
| `synced_at` | `TIMESTAMPTZ` | Y | | | 补传成功入库时间 |
| `idempotency_key` | `VARCHAR(128)`| N | | UQ | **跨事件全局业务幂等键** |
| `payload_hash` | `VARCHAR(64)` | Y | | | **请求载荷 SHA-256 哈希**（用于 409 冲突判断） |
| `clock_offset_at_occurrence`| `BIGINT`| Y | | | 发生时刻边缘时钟偏差快照（ms） |
| `rule_version_used` | `VARCHAR(24)`| Y | | | 边缘判定使用的规则版本快照 |
| `risk_level_code` | `VARCHAR(16)` | N | | | 风险等级机器 Code |
| `payload_summary` | `JSONB` | N | | | 业务要素摘要（人员/设备/区域等，不存视频） |
| `local_linkage` | `JSONB` | Y | | | 边缘本地自治联动执行快照 |
| `status_code` | `VARCHAR(24)` | N | `'PENDING'` | IDX | 队列状态：`PENDING` / `SYNCING` / `SYNCED` / `FAILED` / `DUPLICATE` |
| `retry_count` | `INT` | N | `0` | | 补传重试次数 |
| `last_retry_at` | `TIMESTAMPTZ` | Y | | | 最后重试时间 |
| `last_error` | `TEXT` | Y | | | 补传失败错误信息 |
| `linked_alert_id` | `UUID` | Y | | 软引用 | 补传成功后写入生成的 `safety_alert.id` |
| `sync_delay_sec` | `BIGINT` | Y | | | 补传延迟秒数（`synced_at` - `edge_occurred_at`） |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录创建时间 |

### 24. 运维事件日志 `ops_event_log`（P1）
- **业务定位**：云边协同与系统运维日志流水（Append-only，无 update/delete 路径）。
- **来源 Model**：`OpsEventLog`

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | N | 序列 | PK | 物理代理键（序列 `seq_ops_event_log`） |
| `edge_node_code` | `VARCHAR(32)` | Y | | IDX | 关联边缘节点编号（中心系统日志可为空） |
| `log_level` | `VARCHAR(16)` | N | | | 日志等级：`INFO` / `WARNING` / `ERROR` |
| `event_type` | `VARCHAR(48)` | N | | IDX | 运维事件类型机器 Code（如 `NODE_OFFLINE`、`REPLAY_SUCCESS` 等） |
| `message` | `TEXT` | N | | | 运维日志内容详情 |
| `trace_id` | `VARCHAR(64)` | Y | | | 全链路追踪 Trace ID |
| `occurred_at` | `TIMESTAMPTZ` | N | | IDX | 日志事实发生时间戳 |
| `created_at` | `TIMESTAMPTZ` | N | `now()` | | 记录写入时间戳 |

---

## 八、平台发号辅助表（Platform Auxiliary Table，可选）

### 25. 业务编号计数辅助表 `sys_business_number`
- **业务定位**：按日循环业务编号（如 `ALM-yyyyMMdd-NNN`）多实例并发排他行锁发号器。

| 字段名 | 物理类型 | 可空 | 默认值 | 约束/索引 | 业务含义与说明 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `number_type` | `VARCHAR(32)` | N | | PK(联合) | 业务编号类型：`ALERT` / `AI_EVENT` / `RULE` / `FENCE` |
| `business_date` | `VARCHAR(16)` | N | | PK(联合) | 业务归属日期：`yyyyMMdd` 或 `'GLOBAL'` |
| `current_value` | `BIGINT` | N | `0` | | 当前已派发的最大数值 |
| `lock_version` | `INT` | N | `0` | | 乐观锁版本号 |
| `updated_at` | `TIMESTAMPTZ` | N | `now()` | | 最后发号更新时间 |

---

## 九、延期实施表定义（Deferred Tables，共 5 张）

以下表在当前第一期活动 DDL 中明确不创建，仅以设计规格存档：

1. **`sys_dictionary` / `sys_dictionary_item`（动态业务字典表）**
   - *延期理由*：Phase A 已经将所有状态/风险收敛为强类型机器 Code 和 Java 枚举，前端有统一字典映射器；一期无任何运维端动态增删字典的写路径，建表为纯空壳。
2. **`sys_idempotency_record`（通用 HTTP 持久化幂等表）**
   - *延期理由*：全站已有基于 Kvrocks (Redis 协议) 原生 TTL (600s) 的毫秒级防重机制；短期内无超大时间跨度的跨请求幂等落库需求。
3. **`safety_attachment`（文件对象存储统一台账）**
   - *延期理由*：当前系统未接入 MinIO / S3 等真实对象存储服务，现场照片/截图仍为模拟路径，待存储技术栈确立后再行建表。
4. **`outbox_event`（跨实例事务发件箱）**
   - *延期理由*：单实例阶段由 Spring `TransactionSynchronization.afterCommit` 钩子直发 WebSocket 即可确保事务一致性；待未来引入 RocketMQ 跨实例广播时才需引入。

---

## 十、严禁建在 openGauss 的时序表（Prohibited from openGauss，归 openGemini）

以下 3 类高频时序数据严禁在 openGauss 中建表：
1. **`safety_personnel_track`（人员连续轨迹点）**：每秒多点采样，属于典型时序写入负载。
2. **`collision_measurement`（防碰撞毫米波测距原始点流）**：10~50Hz 高频点阵测距，直接写入 openGemini。
3. **`edge_node_metric`（边缘节点秒级 CPU/内存/网络指标）**：监控采样序列，统一归 openGemini。
