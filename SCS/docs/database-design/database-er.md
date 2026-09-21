# 数据库 ER 关系图（openGauss 6.x 最终设计修订版）

> 只绘制持久化实体表（Master / Entity / Detail），**不绘制 Projection / DTO**（Overview / Screen / Analytics / Mobile / PairState / TrackPoint 等视图对象不建表）。
> 关系记号：`||--o{` 一对多；`||--||` 一对一；虚线表示应用层软引用（无数据库物理强外键）。
> 配套：[`database-design.md`](database-design.md)、[`openGauss-schema-final-draft.sql`](../../backend/src/main/resources/db/design/openGauss-schema-final-draft.sql)。

---

## 1. 主数据与现场人员资产域

```mermaid
erDiagram
    SYS_TEAM ||--o{ SYS_USER : "team_code (软引用)"
    SYS_TEAM ||--o{ SAFETY_PERSONNEL : "team_code (软引用)"
    SAFETY_AREA ||--o{ SAFETY_PERSONNEL : "area_code (软引用)"
    SAFETY_AREA ||--o{ SAFETY_AREA : "parent_id (UNRESOLVED期间全NULL)"
    SYS_USER }o--o{ SAFETY_PERSONNEL : "linked_user_code 预留软引用"

    SYS_TEAM {
        bigint id PK
        varchar team_code UK "LOADING_TEAM_1 等"
        varchar team_name "班组规范名称"
        varchar team_type "INTERNAL / CONTRACTOR"
        boolean enabled "启用状态"
        boolean demo_unverified "Demo未核验标记"
    }

    SAFETY_AREA {
        bigint id PK
        varchar area_code UK "LOADING_AREA_A 等"
        varchar area_name "作业区域中文名"
        varchar area_type "OPERATION / LANE / BLOCK"
        bigint parent_id FK "自关联父级区域"
        boolean demo_unresolved "未确认层级标记"
    }

    SYS_USER {
        bigint id PK
        varchar user_code UK "USR-001 ~ USR-007"
        varchar user_name "真实姓名"
        varchar team_code "所属班组软引用"
        varchar role_code "SAFETY_OFFICER 等"
        varchar role_name_snapshot "角色名称快照"
        varchar shift_code "班次Code"
        boolean enabled "启用状态"
        boolean demo_unverified "Demo未核验标记"
    }

    SAFETY_PERSONNEL {
        bigint id PK
        varchar personnel_code UK "P-ZHAO 等"
        varchar job_no UK "P-24018 工号"
        varchar person_name "姓名"
        varchar team_code "所属班组软引用"
        varchar area_code "当前区域软引用"
        varchar bracelet_code "佩戴手环终端号"
        smallint battery_pct "手环电量 0-100"
        varchar online_status_code "ONLINE / OFFLINE"
        varchar bracelet_status_code "ONLINE / OFFLINE / LOW_BATTERY"
        varchar person_risk_code "NORMAL / ATTENTION / HIGH"
        numeric pos_x "画布百分比坐标 X"
        numeric pos_y "画布百分比坐标 Y"
        varchar linked_user_code "预留系统账号软引用"
        timestamptz last_seen_at "最后心跳时标"
    }
```

---

## 2. 告警处置主聚合域（Alert Aggregate Cluster）

```mermaid
erDiagram
    SAFETY_ALERT ||--o{ SAFETY_ALERT_TIMELINE : "FK alert_id (级联删除)"
    SAFETY_ALERT ||--o{ SAFETY_ALERT_EVIDENCE : "FK alert_id (级联删除)"
    SAFETY_ALERT ||--o{ SAFETY_ALERT_TREATMENT : "FK alert_id (级联删除)"
    SAFETY_ALERT ||--|| SAFETY_ALERT_LINKAGE : "FK 1:1 alert_id (级联删除)"
    SAFETY_ALERT_LINKAGE ||--o{ SAFETY_ALERT_LINKAGE_STEP : "FK linkage_id (级联删除)"

    SYS_USER }o--o{ SAFETY_ALERT : "assignee_user_code 软引用"
    SAFETY_AREA }o--o{ SAFETY_ALERT : "area_code 软引用"
    AI_EVENT }o--o{ SAFETY_ALERT : "linked_ai_event_id 软引用"
    EDGE_PENDING_EVENT }o--o{ SAFETY_ALERT : "linked_alert_id 软引用"

    SAFETY_ALERT {
        uuid id PK "应用层全局 UUID"
        varchar alert_no UK "ALM-yyyyMMdd-NNN"
        varchar title "告警标题"
        varchar source "业务来源"
        varchar event_type "细分事件类型"
        varchar risk_level_code "NORMAL/WARNING/SEVERE/URGENT"
        varchar status_code "PENDING_CONFIRM..CLOSED (无 ESCALATED)"
        varchar previous_risk_level_code "B5-01 升级前风险机器Code"
        varchar mobile_stage_code "PENDING/ACCEPTED/ARRIVED/PROCESSING"
        varchar area_code "发生作业区域软引用"
        varchar target_object_type "PERSONNEL / DEVICE"
        varchar target_object_id "对象业务编号"
        varchar assignee_user_code "指派责任人系统账号"
        varchar decision_source_type "FENCE/RULE/AI_MODEL/COLLISION"
        varchar decision_source_code "决策源编号"
        varchar decision_source_version "决策源版本"
        varchar rule_code "关联规则编码(可空)"
        varchar dedup_key "未闭环排他去重键"
        timestamptz occurred_at "事实发生时间戳"
        timestamptz closed_at "处置完成时间戳"
        int lock_version "乐观锁版本号"
    }

    SAFETY_ALERT_TIMELINE {
        bigint id PK
        uuid alert_id FK "所属告警"
        int sequence_no "聚合内严格递增序号"
        varchar event_type "节点动作类型机器Code"
        varchar state_code "done / active"
        varchar status_after "流转后告警主状态"
        varchar operator_user_code "操作人系统账号"
        varchar operator_name_snapshot "操作人姓名快照"
        text message "节点描述信息"
        jsonb metadata "附加结构化审计参数"
        timestamptz occurred_at "动作发生时间戳"
    }

    SAFETY_ALERT_EVIDENCE {
        bigint id PK
        uuid alert_id FK "所属告警"
        varchar evidence_type "PERSONNEL/COLLISION/AI/METRIC"
        varchar source_code "来源设备/模型编号"
        jsonb payload "多态结构化证据载荷"
        timestamptz occurred_at "采样时间戳"
    }

    SAFETY_ALERT_TREATMENT {
        bigint id PK
        uuid alert_id FK "所属告警"
        jsonb measures "防范措施列表"
        text summary "处置结论说明"
        varchar operator_user_code "处置人系统账号"
        varchar operator_name_snapshot "处置人姓名快照"
        jsonb attachments "附件快照"
        timestamptz submitted_at "提交时间戳"
    }

    SAFETY_ALERT_LINKAGE {
        bigint id PK
        uuid alert_id FK "1:1所属告警"
        varchar status_code "STARTED/FINISHED/FAILED/TAKEOVER"
        timestamptz started_at "启动时间戳"
        timestamptz finished_at "完成时间戳"
    }

    SAFETY_ALERT_LINKAGE_STEP {
        bigint id PK
        bigint linkage_id FK "所属联动会话"
        int step_no "次序 1..6"
        varchar step_name "步骤名称"
        varchar status_code "PENDING/SUCCESS/FAILED/SKIPPED"
        varchar action_text "下发指令摘要"
        timestamptz executed_at "执行时间戳"
    }
```

---

## 3. AI 视觉识别聚合域（AI Aggregate Cluster）

```mermaid
erDiagram
    DEVICE_CAMERA ||--o{ AI_EVENT : "camera_code 软引用"
    SAFETY_AREA ||--o{ DEVICE_CAMERA : "area_code 软引用"
    SAFETY_AREA ||--o{ AI_EVENT : "area_code 软引用"
    AI_EVENT ||--o{ AI_EVENT_TIMELINE : "FK ai_event_id (级联删除)"
    AI_EVENT }o--o{ SAFETY_PERSONNEL : "related_person_code 软引用"
    AI_EVENT }o--o{ COLLISION_DEVICE : "related_device_code 软引用"
    AI_EVENT }o--o{ SAFETY_ALERT : "linked_alert_id 确认派生关联"

    DEVICE_CAMERA {
        bigint id PK
        varchar camera_code UK "CAM-01 ~ CAM-08"
        varchar camera_name "点位名称"
        varchar area_code "所属区域软引用"
        varchar rtsp_url "视频流地址"
        varchar health_code "NORMAL / DEGRADED / OFFLINE"
        boolean ai_enabled "是否启用AI分析"
        varchar assigned_edge_code "边缘节点软引用"
        numeric pos_x "地图坐标 X"
        numeric pos_y "地图坐标 Y"
    }

    AI_EVENT {
        uuid id PK "应用层全局 UUID"
        varchar event_no UK "AI-E-yyyyMMdd-NNN"
        varchar event_type "违规类型"
        varchar camera_code "摄像头软引用"
        varchar area_code "区域软引用"
        numeric confidence "识别置信度"
        varchar status_code "PENDING..CLOSED"
        varchar risk_code "LOW / MEDIUM / HIGH"
        jsonb detection_boxes "目标边界框快照"
        varchar reviewer_user_code "复核人系统账号"
        varchar assignee_user_code "派单责任人账号"
        uuid linked_alert_id "关联生成的 safety_alert.id"
        timestamptz occurred_at "视觉检测时间戳"
    }

    AI_EVENT_TIMELINE {
        bigint id PK
        uuid ai_event_id FK "所属AI事件"
        int sequence_no "顺序编号 1, 2, 3..."
        varchar event_type "动作机器Code"
        varchar state_code "done / active"
        varchar operator_user_code "操作人系统账号"
        text message "节点日志"
        timestamptz occurred_at "发生时间戳"
    }
```

---

## 4. 防碰撞重点设备域（Collision Devices）

```mermaid
erDiagram
    SAFETY_AREA ||--o{ COLLISION_DEVICE : "area_code 软引用"
    COLLISION_DEVICE }o--o{ COLLISION_DEVICE : "related_device_code 配对镜像"

    COLLISION_DEVICE {
        bigint id PK
        varchar device_code UK "VEH-07, TIP-02, CRANE-01"
        varchar device_name "设备显示名称"
        varchar device_type "转运车辆 / 翻箱机 / 龙门吊"
        varchar area_code "作业区域软引用"
        varchar status_code "NORMAL / RUNNING / STANDBY"
        numeric speed "当前速度 m/s"
        varchar direction "运行方向"
        varchar control_status "控制系统状态"
        varchar risk_level_code "SAFE/WARNING/SEVERE/URGENT"
        varchar sensor_health_code "NORMAL/UNCERTAIN/RADAR_DOWN"
        varchar related_device_code "最近配对目标设备"
        numeric pos_x "地图坐标 X"
        numeric pos_y "地图坐标 Y"
        timestamptz last_updated_at "最后更新时间戳"
    }
```

---

## 5. 电子围栏聚合域（Fence Aggregate Cluster）

```mermaid
erDiagram
    SAFETY_AREA ||--o{ SAFETY_FENCE : "area_code 软引用"
    SAFETY_FENCE ||--o{ SAFETY_FENCE_VERSION : "FK fence_id (级联删除)"
    SAFETY_FENCE ||--o{ SAFETY_FENCE_EDGE_SYNC : "FK fence_id (级联删除)"
    EDGE_NODE ||--o{ SAFETY_FENCE_EDGE_SYNC : "edge_node_code 软引用"

    SAFETY_FENCE {
        bigint id PK
        varchar fence_code UK "FENCE-001 ~ FENCE-004"
        varchar fence_name "围栏名称"
        varchar fence_kind "危险区域/临时施工/授权作业"
        varchar area_code "作业区域软引用"
        varchar active_version "当前生效版本指针(如 v1.2)"
        varchar status_code "DRAFT/TO_REVIEW/EFFECTIVE/DISABLED"
        varchar approver_user_code "审批人账号Code"
        timestamptz effective_at "生效时间戳"
        int lock_version "乐观锁版本号"
    }

    SAFETY_FENCE_VERSION {
        bigint id PK
        bigint fence_id FK "所属围栏"
        varchar version_no "版本号 v1.0, v1.1"
        varchar risk_level_code "越界告警风险机器Code"
        varchar status_code "DRAFT / ACTIVE / ARCHIVED"
        jsonb polygon "多边形顶点坐标点集"
        jsonb team_scope "授权班组结构快照"
        varchar created_by_user_code "起草人账号Code"
        timestamptz effective_at "生效时间戳"
    }

    SAFETY_FENCE_EDGE_SYNC {
        bigint id PK
        bigint fence_id FK "所属围栏"
        varchar edge_node_code "目标边缘节点编号"
        varchar expected_version "平台期望版本"
        varchar edge_version "边缘实际上报版本"
        varchar sync_status "synced/syncing/mismatch/offline"
        timestamptz synced_at "同步时间戳"
    }
```

---

## 6. 安全卡控规则聚合域（Rule Aggregate Cluster）

```mermaid
erDiagram
    SAFETY_RULE ||--o{ SAFETY_RULE_AREA : "FK rule_id (级联删除)"
    SAFETY_RULE ||--o{ SAFETY_RULE_VERSION : "FK rule_id (级联删除)"
    SAFETY_RULE ||--o{ SAFETY_RULE_EDGE_SYNC : "FK rule_id (级联删除)"
    SAFETY_AREA ||--o{ SAFETY_RULE_AREA : "area_code 软引用"
    EDGE_NODE ||--o{ SAFETY_RULE_EDGE_SYNC : "edge_node_code 软引用"

    SAFETY_RULE {
        bigint id PK
        varchar rule_code UK "RULE-DEV-003 等"
        rule_name varchar "规则规范名称"
        varchar category "人员安全/设备安全/协同策略"
        varchar active_version "当前线上实际生效版本(未发布NULL，编辑不降级)"
        varchar platform_version "平台编辑最新版本(如草稿v1.0/新草稿v2.5)"
        varchar risk_level_code "触发告警风险机器Code"
        varchar status_code "DRAFT/ACTIVE/DISABLED (编辑不降级)"
        boolean high_risk "高危规则标记"
        varchar owner_user_code "负责人账号Code"
        varchar approver_user_code "审批人账号Code"
        timestamptz effective_at "生效时间戳"
        int lock_version "乐观锁版本号"
    }

    SAFETY_RULE_AREA {
        bigint id PK
        bigint rule_id FK "所属规则"
        varchar area_code "适用区域编码"
    }

    SAFETY_RULE_VERSION {
        bigint id PK
        bigint rule_id FK "所属规则"
        varchar version_no "版本号 v1.0, v2.4, v2.5"
        varchar status_code "DRAFT/REVIEW/ACTIVE/ARCHIVED"
        jsonb params "参数阈值结构化快照"
        jsonb actions "触发联动动作列表"
        varchar source_version "回滚/修订来源版本"
        jsonb version_diffs "差异摘要快照"
        varchar author_user_code "版本起草人账号Code"
    }

    SAFETY_RULE_EDGE_SYNC {
        bigint id PK
        bigint rule_id FK "所属规则"
        varchar edge_node_code "目标边缘节点编号"
        varchar expected_version "平台期望生效版本"
        varchar edge_version "边缘实际上报版本"
        varchar sync_status "synced/syncing/mismatch/offline"
        timestamptz synced_at "同步时间戳"
    }
```

---

## 7. 边缘离线队列与运维审计域（Edge & Ops Logs）

```mermaid
erDiagram
    EDGE_NODE ||--o{ EDGE_PENDING_EVENT : "edge_node_code 软引用"
    EDGE_NODE ||--o{ OPS_EVENT_LOG : "edge_node_code 软引用"
    EDGE_PENDING_EVENT }o--o{ SAFETY_ALERT : "linked_alert_id 补传生成软引用"

    EDGE_NODE {
        bigint id PK
        varchar node_code UK "EDGE-01 ~ EDGE-04"
        varchar node_name "节点规范名称"
        varchar area_code "部署作业区域"
        varchar ip_address "节点内网IP"
        varchar node_status_code "ONLINE/DEGRADED/OFFLINE/ERROR"
        boolean cloud_connected "云端连接状态"
        boolean autonomy_active "离线自治激活标记"
        varchar active_rule_version "实际运行规则版本"
        varchar active_fence_version "实际运行围栏版本"
        bigint clock_offset_ms "时钟偏差毫秒"
        int queue_depth "离线排队深度"
        timestamptz last_heartbeat_at "最后心跳时间戳"
    }

    EDGE_PENDING_EVENT {
        uuid id PK "全局唯一 UUID"
        varchar event_id "边缘端本地事件编号"
        varchar edge_node_code "来源边缘节点编号"
        varchar event_type "事件类型机器Code"
        varchar idempotency_key UK "跨事件全局业务幂等键"
        varchar payload_hash "载荷SHA-256哈希(409判定)"
        varchar risk_level_code "风险等级机器Code"
        varchar status_code "PENDING/SYNCING/SYNCED/DUPLICATE"
        jsonb payload_summary "业务要素结构化快照"
        jsonb local_linkage "边缘自治联动快照"
        uuid linked_alert_id "补传成功写入的 safety_alert.id"
        timestamptz edge_occurred_at "边缘发生时标(冻结时钟)"
        timestamptz synced_at "补传成功时间戳"
    }

    OPS_EVENT_LOG {
        bigint id PK
        varchar edge_node_code "关联边缘节点编号(可空)"
        varchar log_level "INFO / WARNING / ERROR"
        varchar event_type "运维事件类型机器Code"
        text message "运维日志内容"
        varchar trace_id "全链路追踪ID"
        timestamptz occurred_at "事实发生时间戳"
    }
```
