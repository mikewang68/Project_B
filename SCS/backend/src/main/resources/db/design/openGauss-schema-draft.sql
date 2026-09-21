-- =============================================================================
-- SUPERSEDED DESIGN DRAFT
-- Kept for history only.
-- Do not execute.
-- See openGauss-schema-final-draft.sql
-- =============================================================================
-- DESIGN DRAFT ONLY -- DO NOT APPLY TO PRODUCTION
-- =============================================================================
-- 智慧货场 S3「装卸作业安全卡控系统」
-- Backend Demo (Spring Boot 3.5.5 / Java 17, InMemory Repository)
--   -> openGauss 6.x 持久化蓝图（DDL 草案）
--
-- 本文件性质：
--   * 仅为人工审查的设计草案，不是可执行迁移脚本。
--   * 未接入 Spring Boot 启动流程：工程没有 Flyway / Liquibase / JPA / MyBatis，
--     classpath 下的 db/design/*.sql 不会被自动执行（已在 database-design.md 说明）。
--   * 未在任何 openGauss 实例上执行、验证；目标为 openGauss 6.x（PostgreSQL 线协议，
--     驱动 org.postgresql.Driver，见 application.yml）。
--   * 禁止在本阶段直接 psql/gsql 执行；正式落地前必须先经人工评审、补迁移工具、
--     在 openGauss 6.x 实测兼容性。
--
-- 标注约定：
--   [P0] 第一轮落地（主数据 + 处置主链 + 配置版本）
--   [P1] 第二轮落地（设备/边缘台账、同步、审计、附件、Outbox）
--   [P2] 后续或由时序平台（openGemini）替代，仅给边界草案
--   VERIFY ON OPENGAUSS = openGauss 6.x 兼容性需在真实实例实测，不得想当然
--
-- 反推来源（以代码现状为准）：
--   module/{alert,ai,personnel,fence,collision,rule,ops,analytics,projection}
--   support/masterdata/DemoMasterData.java
--   docs/demo-master-data.md, docs/database-design/database-design.md
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0. Schema 与通用约定
-- -----------------------------------------------------------------------------
-- VERIFY ON OPENGAUSS: 独立 schema 的创建权限与 search_path 配置；
-- 如运维要求使用 public 或业务专属 schema，仅需替换本文件 schema 名。
CREATE SCHEMA IF NOT EXISTS safety;

-- 通用约定（详见 database-design.md）：
--   * 物理主键：主数据/配置/子表明细用 BIGINT 代理键（SEQUENCE）；
--     事件表（alert / ai_event / edge_pending_event）用应用层生成的 UUID，
--     兼容边缘离线生成、多节点、补传幂等，业务编号（ALM-* / AI-E-*）另设 UNIQUE。
--   * 时间：统一 timestamp with time zone（timestamptz），Java 侧 OffsetDateTime；
--     业务时间区分 occurred_at（事件发生，可为边缘时钟）/ received_at（中心接收）/
--     synced_at（补传完成）/ created_at,updated_at（库记录生命周期）。
--   * 状态/等级：VARCHAR 存稳定英文 code（CHECK 约束枚举合法值），
--     中文展示由字典/前端映射；现有 API 仍返回中文，迁移期允许 *_label 快照列。
--   * 布尔：BOOLEAN；软删除：is_deleted BOOLEAN DEFAULT FALSE（仅主数据/配置表）。
--   * 乐观锁：配置/处置主表 version INT（与业务“版本号 v3.x”是两个概念，
--     业务版本号见 *_version_no 字符串列）。
--   * JSONB：仅用于多态证据、参数/动作快照、多边形、检测框、摘要等非查询主轴字段。
--   * 命名：snake_case；索引 idx_<table>_<cols>；唯一 uq_<table>_<col>；外键 fk_*。
--   * 本草案不创建任何数据库 ROLE / USER / 密码；不存任何明文密钥。

-- =============================================================================
-- 1. 主数据 / 组织区域（Master / Reference Data）—— 全部 [P0]
--    权威来源：DemoMasterData（生产前替换为正式组织/区域主数据）
-- =============================================================================

-- 1.1 班组 sys_team -----------------------------------------------------------
CREATE TABLE safety.sys_team (
    id              BIGINT       NOT NULL,
    team_code       VARCHAR(48)  NOT NULL,
    team_name       VARCHAR(64)  NOT NULL,
    team_type       VARCHAR(24)  NOT NULL DEFAULT 'INTERNAL',  -- INTERNAL 内部班组 / CONTRACTOR 外协 / QUALITY 等
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    demo_unverified BOOLEAN      NOT NULL DEFAULT FALSE,       -- Demo 待权威核验标记
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    CONSTRAINT pk_sys_team PRIMARY KEY (id),
    CONSTRAINT uq_sys_team_code UNIQUE (team_code)
);
COMMENT ON TABLE  safety.sys_team IS '班组主数据（Demo：6 个，code 见 demo-master-data.md）';
COMMENT ON COLUMN safety.sys_team.team_type IS 'INTERNAL/CONTRACTOR/QUALITY；外协单位、质量管理作为独立类别保留，不猜测合并';

-- 1.2 作业区域 safety_area -----------------------------------------------------
CREATE TABLE safety.safety_area (
    id              BIGINT       NOT NULL,
    area_code       VARCHAR(48)  NOT NULL,
    area_name       VARCHAR(64)  NOT NULL,
    area_type       VARCHAR(32)  NOT NULL DEFAULT 'OPERATION', -- OPERATION/LANE/BLOCK/FACILITY ...
    parent_id       BIGINT,                                   -- 区域层级；UNRESOLVED 期间一律 NULL
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    demo_unresolved BOOLEAN      NOT NULL DEFAULT FALSE,      -- 箱区/通道等未确认关系置 TRUE
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    CONSTRAINT pk_safety_area PRIMARY KEY (id),
    CONSTRAINT uq_safety_area_code UNIQUE (area_code),
    CONSTRAINT fk_safety_area_parent FOREIGN KEY (parent_id)
        REFERENCES safety.safety_area (id)
);
COMMENT ON TABLE safety.safety_area IS '作业区域主数据（Demo：12 个）。箱区 A/B、箱区通道 C、维修通道、铁路装卸线 B、机房保持独立，parent_id 暂不填';

-- 1.3 系统用户/责任人 sys_user（USR-*，登录/派单/复核/审批/操作审计）---------------
CREATE TABLE safety.sys_user (
    id              BIGINT       NOT NULL,
    user_code       VARCHAR(32)  NOT NULL,                    -- USR-001 ...
    user_name       VARCHAR(64)  NOT NULL,
    team_code       VARCHAR(48),                               -- 软外键倾向：见 design §外键策略
    role_code       VARCHAR(48),                               -- 角色 code（Demo：安全员等，中文 role_name 走字典）
    role_name       VARCHAR(64),                               -- Demo 快照/展示（API 仍返回中文角色）
    shift_code      VARCHAR(24),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    demo_unverified BOOLEAN      NOT NULL DEFAULT FALSE,      -- USR-005~007 = TRUE
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    is_deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_sys_user PRIMARY KEY (id),
    CONSTRAINT uq_sys_user_code UNIQUE (user_code)
    -- 推荐 FK：team_code -> sys_team.team_code（code 唯一键，可建外键；
    -- 若未来用户来自外部 IAM、team 可能先于主数据到达，则降级为软引用，见 open questions）
);
COMMENT ON TABLE safety.sys_user IS '系统用户/责任人（USR-*）。不含登录口令；认证正式接入 IAM/SSO，密码/Token 严禁入库';

-- =============================================================================
-- 2. 现场作业人员与设备主数据
-- =============================================================================

-- 2.1 现场作业人员 safety_personnel（P-*，定位/手环/越界；与 sys_user 严格分开）[P0]
CREATE TABLE safety.safety_personnel (
    id                  BIGINT       NOT NULL,
    personnel_code      VARCHAR(32)  NOT NULL,                -- P-ZHAO / P-002 ...
    job_no              VARCHAR(32),                          -- 工号 P-24018 / 外协 V-10012
    person_name         VARCHAR(64)  NOT NULL,
    team_code           VARCHAR(48),
    area_code           VARCHAR(48),                          -- 当前所在区域（实时态，随定位更新）
    bracelet_id         VARCHAR(32),                          -- WB-018
    bracelet_status     VARCHAR(24),                          -- 在线/离线/低电量（迁移期中文，远期 code）
    battery_pct         SMALLINT,
    online_status       VARCHAR(16),                          -- 在线/离线（status）
    person_state        VARCHAR(24),                          -- normal/warning/danger/offline（state）
    positioning_quality VARCHAR(24),                          -- 定位优秀/良好/较低/无信号
    risk_label          VARCHAR(16),                          -- 正常/关注/高风险（感知三档，非 Alert 四级）
    pos_x               NUMERIC(8,3),                         -- Demo 0-100 百分比画布坐标
    pos_y               NUMERIC(8,3),
    coordinate_text     VARCHAR(64),
    distance_today_m    NUMERIC(10,2),
    alerts_today        INT          NOT NULL DEFAULT 0,
    linked_user_code    VARCHAR(32),                          -- 预留：未来与 sys_user/IAM 统一，不强制
    last_seen_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    is_deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_safety_personnel PRIMARY KEY (id),
    CONSTRAINT uq_personnel_code UNIQUE (personnel_code),
    CONSTRAINT uq_personnel_job_no UNIQUE (job_no),
    CONSTRAINT chk_battery CHECK (battery_pct IS NULL OR (battery_pct BETWEEN 0 AND 100))
);
CREATE INDEX idx_personnel_team ON safety.safety_personnel (team_code);
CREATE INDEX idx_personnel_area ON safety.safety_personnel (area_code);
CREATE INDEX idx_personnel_state ON safety.safety_personnel (person_state);
COMMENT ON TABLE safety.safety_personnel IS '现场作业人员（P-*）。当前位置/电量为“最新态”，高频历史轨迹不入本表（见 safety_personnel_track / openGemini）';

-- 2.2 摄像头 device_camera [P1]（Demo 8 路，AiDemoSeeder 台账）
CREATE TABLE safety.device_camera (
    id              BIGINT       NOT NULL,
    camera_code     VARCHAR(32)  NOT NULL,                    -- CAM-01 ...
    camera_name     VARCHAR(96)  NOT NULL,
    area_code       VARCHAR(48),
    online          BOOLEAN      NOT NULL DEFAULT TRUE,
    quality_pct     NUMERIC(5,2),
    health_status   VARCHAR(24),                              -- 正常/画面质量下降/离线（远期 code）
    model_profile   VARCHAR(64),                              -- 绑定 AI 模型档位（球机/枪机），可空
    stream_ref      VARCHAR(255),                             -- 流引用 KEY/标识；禁止存 RTSP 带密钥 URL
    last_seen_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    is_deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_device_camera PRIMARY KEY (id),
    CONSTRAINT uq_camera_code UNIQUE (camera_code)
);
COMMENT ON TABLE safety.device_camera IS '摄像头台账。RTSP 账号密码、证书等属于安全配置，经环境变量/密钥管理注入，不入库';

-- 2.3 防碰撞设备 collision_device [P1]（主数据 + 最新态；高频测量分离）
CREATE TABLE safety.collision_device (
    id                      BIGINT       NOT NULL,
    device_code             VARCHAR(32)  NOT NULL,            -- VEH-07 / TIP-02 / CRANE-01
    device_name             VARCHAR(96)  NOT NULL,
    device_type             VARCHAR(32),                      -- 转运车辆/翻箱机/龙门吊
    area_code               VARCHAR(48),
    run_status              VARCHAR(24),                      -- 运行中/作业中（设备运行态）
    speed                   NUMERIC(8,2),
    direction               VARCHAR(32),
    control_status          VARCHAR(24),                      -- PLC/控制状态
    communication_status    VARCHAR(24),                      -- 通信状态
    radar_status            VARCHAR(24),                      -- 雷达状态
    risk_label              VARCHAR(16),                      -- 安全/预警/严重/紧急/待确认（设备侧风险）
    related_device_code     VARCHAR(32),                      -- 当前配对设备（最新配对，运行态）
    latest_alert_id         UUID,                              -- 最近告警（软引用 safety_alert）
    pos_x                   NUMERIC(8,3),
    pos_y                   NUMERIC(8,3),
    last_updated_at         TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ,
    is_deleted              BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_collision_device PRIMARY KEY (id),
    CONSTRAINT uq_collision_device_code UNIQUE (device_code)
);
CREATE INDEX idx_collision_device_area ON safety.collision_device (area_code);
COMMENT ON TABLE safety.collision_device IS '防碰撞设备主数据与最新状态。distance/relSpeed/trend 等高频测量不入本表（见 collision_measurement / openGemini）';

-- 2.4 边缘节点 edge_node [P1]（EDGE-01~04，资产 + 最新状态；高频指标分离）
CREATE TABLE safety.edge_node (
    id                       BIGINT       NOT NULL,
    node_code                VARCHAR(32)  NOT NULL,           -- EDGE-01 ...
    node_name                VARCHAR(96)  NOT NULL,
    area_code                VARCHAR(48),
    ip_address               VARCHAR(45),                    -- 内网 IP，非密钥
    node_status              VARCHAR(16)  NOT NULL DEFAULT 'OFFLINE', -- ONLINE/DEGRADED/OFFLINE/RECOVERING/ERROR
    cloud_connected          BOOLEAN      NOT NULL DEFAULT FALSE,
    autonomy_active          BOOLEAN      NOT NULL DEFAULT FALSE,
    agent_version            VARCHAR(32),
    active_rule_version      VARCHAR(24),
    expected_rule_version    VARCHAR(24),
    active_fence_version     VARCHAR(24),
    expected_fence_version   VARCHAR(24),
    clock_offset_ms          BIGINT,
    latency_ms               INT,                             -- 最新值（最新态）
    cpu_usage_pct            SMALLINT,                        -- 最新值
    memory_usage_pct         SMALLINT,                        -- 最新值
    disk_usage_pct           SMALLINT,
    temperature_c            NUMERIC(5,1),
    queue_depth              INT          NOT NULL DEFAULT 0,
    cached_event_count       INT          NOT NULL DEFAULT 0,
    cache_parts              JSONB,                           -- [{label,percent}] 最新缓存构成
    recovery_phase           VARCHAR(32),                     -- 当前恢复阶段 code
    recovery_state           JSONB,                           -- 5 阶段时间线最新快照（运行态；历史见 ops_event_log）
    uptime_sec               BIGINT,
    last_heartbeat_at        TIMESTAMPTZ,
    last_sync_at             TIMESTAMPTZ,
    last_error               TEXT,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ,
    is_deleted               BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_edge_node PRIMARY KEY (id),
    CONSTRAINT uq_edge_node_code UNIQUE (node_code),
    CONSTRAINT chk_edge_status CHECK (node_status IN
        ('ONLINE','DEGRADED','OFFLINE','RECOVERING','ERROR'))
);
CREATE INDEX idx_edge_node_status ON safety.edge_node (node_status);
COMMENT ON TABLE safety.edge_node IS '边缘节点资产与最新状态。CPU/内存/延迟历史曲线不写本表（见 edge_node_metric / openGemini）';

-- =============================================================================
-- 3. Alert 处置主链 [P0]
--    来源：module/alert/model/DemoAlert(+EdgeReplayMeta)、TimelineEvent、AlertEvidence、
--          TreatmentRecord、LinkageStep；AlertStatuses / MobileStages
-- =============================================================================

-- 3.1 告警主表 safety_alert ----------------------------------------------------
CREATE TABLE safety.safety_alert (
    id                       UUID         NOT NULL,           -- 物理主键（应用生成 UUID，兼容边缘/分布式）
    alert_no                 VARCHAR(40)  NOT NULL,           -- 业务编号 ALM-yyyyMMdd-NNN / ALM-DEMO-RISK
    title                    VARCHAR(200) NOT NULL,
    source                   VARCHAR(32),                     -- 人员安全/设备防碰撞/AI违规/设备异常/系统异常/EDGE_REPLAY
    event_type               VARCHAR(48),                     -- 人员越界/设备距离风险/未佩戴安全帽...（迁移期中文，远期 code）
    risk_level               VARCHAR(16),                     -- 一般/预警/严重/紧急（迁移期中文，远期 NORMAL/WARNING/SEVERE/URGENT）
    status_code              VARCHAR(24),                     -- 英文 code（PENDING_CONFIRM/.../CLOSED/ESCALATED）
    status_label             VARCHAR(24),                     -- 中文快照（待确认/.../已关闭），过渡期 API 直接使用
    mobile_stage             VARCHAR(16),                     -- PENDING/ACCEPTED/ARRIVED/PROCESSING（移动端细阶段）
    priority                 VARCHAR(16),
    -- 区域：code 关联 + 名称快照
    area_code                VARCHAR(48),
    area_name_snapshot       VARCHAR(64),
    -- 对象（target）：多态对象，软引用 + 名称快照
    object_type              VARCHAR(24),                     -- personnel/collision/ai/device/system
    object_id                VARCHAR(48),
    object_name_snapshot     VARCHAR(128),
    -- 责任人：正式关联以 user_code 为准（解决当前按姓名落库技术债），姓名做快照
    assignee_user_code       VARCHAR(32),
    assignee_name_snapshot   VARCHAR(64),
    confirm_user_code        VARCHAR(32),
    confirm_user_name        VARCHAR(64),
    review_user_code         VARCHAR(32),
    review_user_name         VARCHAR(64),
    review_note              TEXT,
    -- 规则血缘（AI 来源告警可能为空）
    rule_id                  VARCHAR(40),
    rule_version             VARCHAR(24),
    upgraded_from_alert_no   VARCHAR(40),                     -- 升级来源（同事件升级同单时记录原编号）
    linked_ai_event_id       UUID,                            -- AI 来源事件（软引用 ai_event.id）
    -- 边缘补传元数据（DemoAlert.EdgeReplayMeta）
    origin                   VARCHAR(24)  DEFAULT 'CLOUD',    -- CLOUD / EDGE_REPLAY
    edge_node_code           VARCHAR(32),
    offline_event_id         VARCHAR(64),
    offline_occurred         BOOLEAN      DEFAULT FALSE,
    rule_version_used        VARCHAR(24),
    sync_delay_sec           BIGINT,
    synced_at                TIMESTAMPTZ,
    -- 去重键（PERSON_INTRUSION:* / COLLISION:* / EDGE-REPLAY:*）
    dedup_key                VARCHAR(160),
    -- SLA / 关键时间
    duration_sec             INT,
    sla_limit_min            INT,
    sla_remaining_sec        BIGINT,
    sla_deadline             TIMESTAMPTZ,
    occurred_at              TIMESTAMPTZ,                     -- 事件发生时间（边缘事件用边缘时钟，不回改）
    detected_at              TIMESTAMPTZ,
    received_at              TIMESTAMPTZ,                     -- 中心接收时间
    confirmed_at             TIMESTAMPTZ,
    accepted_at              TIMESTAMPTZ,
    arrived_at               TIMESTAMPTZ,
    closed_at                TIMESTAMPTZ,
    -- 联动汇总（明细在子表）
    linkage_available        BOOLEAN      DEFAULT FALSE,
    linkage_finished         BOOLEAN      DEFAULT FALSE,
    linkage_failed           BOOLEAN      DEFAULT FALSE,
    takeover                 BOOLEAN      DEFAULT FALSE,
    -- 审计 / 乐观锁
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ,
    lock_version             INT          NOT NULL DEFAULT 0,  -- 多终端并发处置乐观锁
    CONSTRAINT pk_safety_alert PRIMARY KEY (id),
    CONSTRAINT uq_alert_no UNIQUE (alert_no),
    CONSTRAINT uq_alert_dedup_open UNIQUE (dedup_key, status_code)
    -- 注：去重唯一约束仅需约束“未关闭告警”。开放状态集合 >1，无法直接用部分唯一索引表达，
    -- 方案 A（推荐）：部分唯一索引 WHERE closed_at IS NULL（见下）；
    -- 方案 B：应用层 findOpenByDedupKey + 事务串行。VERIFY ON OPENGAUSS 部分索引行为。
);
CREATE UNIQUE INDEX uq_alert_dedup_open
    ON safety.safety_alert (dedup_key)
    WHERE closed_at IS NULL;  -- VERIFY ON OPENGAUSS: partial index
CREATE INDEX idx_alert_status_occurred ON safety.safety_alert (status_code, occurred_at DESC);
CREATE INDEX idx_alert_area_occurred   ON safety.safety_alert (area_code, occurred_at DESC);
CREATE INDEX idx_alert_level_occurred  ON safety.safety_alert (risk_level, occurred_at DESC);
CREATE INDEX idx_alert_assignee        ON safety.safety_alert (assignee_user_code);
CREATE INDEX idx_alert_type_occurred   ON safety.safety_alert (event_type, occurred_at DESC);
CREATE INDEX idx_alert_linked_ai       ON safety.safety_alert (linked_ai_event_id);
-- 列表筛选复合索引（AlertQuery: risk/status/area/eventType/source/assignee + 时间范围分页）
CREATE INDEX idx_alert_filter_combo
    ON safety.safety_alert (status_code, risk_level, area_code, occurred_at DESC);
COMMENT ON TABLE safety.safety_alert IS '安全告警（唯一处置主链根）。事件表不物理删除、不软删；USR 关联用 code，名称留快照';

-- 3.2 告警时间线 safety_alert_timeline（append-only，稳定排序不依赖 List 顺序）
CREATE TABLE safety.safety_alert_timeline (
    id                   BIGINT       NOT NULL,
    alert_id             UUID         NOT NULL,
    sequence_no          INT          NOT NULL,
    event_type           VARCHAR(48),                        -- 动作类型 code（confirm/assign/start/...）
    event_name           VARCHAR(96),
    state_code           VARCHAR(16)  DEFAULT 'done',         -- done/active/wait/...
    status_after         VARCHAR(24),                         -- 该节点后告警状态
    operator_user_code   VARCHAR(32),
    operator_name_snapshot VARCHAR(64),
    message              TEXT,
    metadata             JSONB,
    occurred_at          TIMESTAMPTZ  NOT NULL,               -- 对应 TimelineEvent.at
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    trace_id             VARCHAR(64),
    CONSTRAINT pk_alert_timeline PRIMARY KEY (id),
    CONSTRAINT uq_alert_timeline_seq UNIQUE (alert_id, sequence_no),
    CONSTRAINT fk_alert_timeline_alert FOREIGN KEY (alert_id)
        REFERENCES safety.safety_alert (id)
);
CREATE INDEX idx_alert_timeline_time ON safety.safety_alert_timeline (alert_id, occurred_at);
COMMENT ON TABLE safety.safety_alert_timeline IS '告警审计时间线，只追加不修改不删除';

-- 3.3 告警证据 safety_alert_evidence（推荐：通用列 relational + 类型特有字段 JSONB）
CREATE TABLE safety.safety_alert_evidence (
    id              BIGINT       NOT NULL,
    alert_id        UUID         NOT NULL,
    evidence_type   VARCHAR(24)  NOT NULL,   -- personnel/collision/ai/device-metric/system-metric
    source_type     VARCHAR(24),             -- 来源对象类型（personnel/device/camera/ai_event/system）
    source_id       VARCHAR(48),             -- 来源对象业务 ID（P-ZHAO / VEH-07 / CAM-01 / AI-E-*）
    occurred_at     TIMESTAMPTZ,
    payload         JSONB        NOT NULL,   -- 类型特有内容：
                                             -- personnel: {track:[{x,y}],currentPosition,fence,band,bandState,heartRate}
                                             -- collision: {distance,relSpeed,trend:[],radar,brakeDistance}
                                             -- ai: {scene,boxes:[{id,label,score,x,y,w,h,tone}],confidence,model,camera,time}
                                             -- metric: {metrics:[{label,value,tone}],description}
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_alert_evidence PRIMARY KEY (id),
    CONSTRAINT fk_alert_evidence_alert FOREIGN KEY (alert_id)
        REFERENCES safety.safety_alert (id),
    CONSTRAINT chk_evidence_type CHECK (evidence_type IN
        ('personnel','collision','ai','device-metric','system-metric'))
);
CREATE INDEX idx_alert_evidence_source ON safety.safety_alert_evidence (source_type, source_id);
COMMENT ON TABLE safety.safety_alert_evidence IS '证据单表+JSONB：五型证据通用列一致、特有结构多变且不参与 WHERE 主轴；不为每型证据建子表（理由见 design §Evidence）';

-- 3.4 处置记录 safety_alert_treatment（1:N，支持驳回后再次处置）
CREATE TABLE safety.safety_alert_treatment (
    id                   BIGINT       NOT NULL,
    alert_id             UUID         NOT NULL,
    sequence_no          INT          NOT NULL,
    operator_user_code   VARCHAR(32),
    operator_name_snapshot VARCHAR(64),
    measures             JSONB        NOT NULL,               -- List<String> 处置措施
    result_text          VARCHAR(128),                        -- result（风险已解除/...）
    risk_resolved        BOOLEAN,
    note                 TEXT,
    attachment_ref       JSONB,                               -- [{resourceId,storageKey,name,size,contentType}]，不存 BLOB
    submitted_at         TIMESTAMPTZ  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_alert_treatment PRIMARY KEY (id),
    CONSTRAINT uq_alert_treatment_seq UNIQUE (alert_id, sequence_no),
    CONSTRAINT fk_alert_treatment_alert FOREIGN KEY (alert_id)
        REFERENCES safety.safety_alert (id)
);
COMMENT ON TABLE safety.safety_alert_treatment IS '处置结果 1:N；TreatmentRecord.attachment 当前仅文本计数，正式改附件引用';

-- 3.5 联动会话 safety_alert_linkage（一次告警的联动执行汇总，1:1）
CREATE TABLE safety.safety_alert_linkage (
    id              BIGINT       NOT NULL,
    alert_id        UUID         NOT NULL,
    requested_mode  VARCHAR(16),                               -- success/fail/manual（LinkageRequest.mode）
    finished        BOOLEAN      NOT NULL DEFAULT FALSE,
    failed          BOOLEAN      NOT NULL DEFAULT FALSE,
    takeover        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    CONSTRAINT pk_alert_linkage PRIMARY KEY (id),
    CONSTRAINT uq_alert_linkage_alert UNIQUE (alert_id),
    CONSTRAINT fk_alert_linkage_alert FOREIGN KEY (alert_id)
        REFERENCES safety.safety_alert (id)
);

-- 3.6 联动步骤 safety_alert_linkage_step（detect/alarm/driver/slow/stop/plc + 人工接管）
CREATE TABLE safety.safety_alert_linkage_step (
    id              BIGINT       NOT NULL,
    linkage_id      BIGINT       NOT NULL,
    alert_id        UUID         NOT NULL,                    -- 冗余便于直接查询
    step_code       VARCHAR(24)  NOT NULL,                    -- detect/alarm/driver/slow/stop/plc/manual_takeover
    step_name       VARCHAR(64),
    sequence_no     INT          NOT NULL,
    state_code      VARCHAR(16)  NOT NULL DEFAULT 'wait',     -- wait/running/done/failed/skipped/success
    detail          TEXT,
    error_code      VARCHAR(48),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_alert_linkage_step PRIMARY KEY (id),
    CONSTRAINT uq_alert_linkage_step UNIQUE (alert_id, sequence_no),
    CONSTRAINT fk_linkage_step_linkage FOREIGN KEY (linkage_id)
        REFERENCES safety.safety_alert_linkage (id),
    CONSTRAINT fk_linkage_step_alert FOREIGN KEY (alert_id)
        REFERENCES safety.safety_alert (id)
);
CREATE INDEX idx_linkage_step_code ON safety.safety_alert_linkage_step (step_code);
COMMENT ON TABLE safety.safety_alert_linkage_step IS '设备侧 CollisionStep 仅为回显缓存，联动权威以本表为准（见 safety-domain issues §6）';

-- =============================================================================
-- 4. AI 违规识别 [P0]
--    来源：DemoAiEvent / AiTimelineNode / AiBox / CameraInfo / AiReviewStatuses
-- =============================================================================

-- 4.1 AI 事件 ai_event --------------------------------------------------------
CREATE TABLE safety.ai_event (
    id                     UUID         NOT NULL,
    event_no               VARCHAR(40)  NOT NULL,             -- AI-E-yyyyMMdd-NNN
    event_type             VARCHAR(48)  NOT NULL,             -- 未佩戴安全帽/翻越护栏/闯入危险区域/人员滞留/摄像头异常
    camera_code            VARCHAR(32),                       -- CAM-01
    camera_name_snapshot   VARCHAR(96),
    area_code              VARCHAR(48),
    area_name_snapshot     VARCHAR(64),
    confidence_pct         NUMERIC(6,2),
    duration_sec           NUMERIC(8,2),
    threshold_pct          NUMERIC(6,2),
    model_version          VARCHAR(64),                       -- PPE-Detection-v2.4.1（检测模型血缘，独立于规则版本）
    health_status          VARCHAR(24),                       -- 摄像头健康（摄像头异常事件用）
    scene_text             TEXT,                              -- scene 描述
    detection_boxes        JSONB,                             -- AiBox[]：[{id,label,score,x,y,w,h,tone}]，展示型、随事件冻结
    rule_ref               VARCHAR(40),                       -- AI 侧规则引用（Demo 多为空）
    related_person_code    VARCHAR(32),                       -- relatedPerson（软引用 P-*）
    related_device_code    VARCHAR(32),
    judge_text             TEXT,
    status_code            VARCHAR(24),                       -- PENDING/CONFIRMED/FALSE_POSITIVE/UNCERTAIN/ASSIGNED/PROCESSING/CLOSED
    status_label           VARCHAR(24),                       -- 中文快照
    risk_label             VARCHAR(16),                       -- AI 三档 高/中/低（与 Alert 四级不同，保留独立语义）
    false_reason_code      VARCHAR(32),                       -- 遮挡误判/光照/识别错误/区域配置/其他（远期 code）
    false_reason_note      TEXT,
    reviewer_user_code     VARCHAR(32),
    reviewer_name_snapshot VARCHAR(64),
    reviewed_at            TIMESTAMPTZ,
    assignee_user_code     VARCHAR(32),                       -- 技术债迁移目标：当前 API 仅传姓名
    assignee_name_snapshot VARCHAR(64),
    assignment_priority    VARCHAR(16),
    assignment_note        TEXT,
    process_status         VARCHAR(24),
    linked_alert_id        UUID,                              -- 确认违规后关联 Alert（软引用/FK 评估见 design）
    is_fresh               BOOLEAN      DEFAULT TRUE,
    occurred_at            TIMESTAMPTZ  NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ,
    lock_version           INT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_ai_event PRIMARY KEY (id),
    CONSTRAINT uq_ai_event_no UNIQUE (event_no)
);
CREATE INDEX idx_ai_status_occurred ON safety.ai_event (status_code, occurred_at DESC);
CREATE INDEX idx_ai_camera_occurred ON safety.ai_event (camera_code, occurred_at DESC);
CREATE INDEX idx_ai_area_occurred   ON safety.ai_event (area_code, occurred_at DESC);
CREATE INDEX idx_ai_type_occurred   ON safety.ai_event (event_type, occurred_at DESC);
CREATE INDEX idx_ai_linked_alert    ON safety.ai_event (linked_alert_id);
-- AiEventQuery: type/area/camera/status/risk/confidence + 关键字 + 时间桶分页
CREATE INDEX idx_ai_filter_combo
    ON safety.ai_event (status_code, event_type, area_code, occurred_at DESC);
COMMENT ON TABLE safety.ai_event IS 'AI 检测/复核事件，与 Alert 不同实体；仅确认违规/派单链路创建 Alert 并以 linked_alert_id 关联';

-- 4.2 AI 时间线 ai_event_timeline（保持领域边界，不与 Alert Timeline 合表）
CREATE TABLE safety.ai_event_timeline (
    id           BIGINT       NOT NULL,
    ai_event_id  UUID         NOT NULL,
    sequence_no  INT          NOT NULL,
    message      TEXT,
    state_code   VARCHAR(16),
    occurred_at  TIMESTAMPTZ  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ai_timeline PRIMARY KEY (id),
    CONSTRAINT uq_ai_timeline_seq UNIQUE (ai_event_id, sequence_no),
    CONSTRAINT fk_ai_timeline_event FOREIGN KEY (ai_event_id)
        REFERENCES safety.ai_event (id)
);

-- =============================================================================
-- 5. 电子围栏 [P0 主表/版本；P1 边缘同步]
--    来源：DemoFence / FencePoint / fence.model.EdgeNode / FenceStatuses
-- =============================================================================

-- 5.1 围栏主表 safety_fence（身份 + 当前状态）
CREATE TABLE safety.safety_fence (
    id                   BIGINT       NOT NULL,
    fence_code           VARCHAR(32)  NOT NULL,              -- FENCE-001 ...
    fence_name           VARCHAR(96)  NOT NULL,
    kind_code            VARCHAR(32),                        -- 危险区域/设备区域/临时围栏/预警区域（远期 code）
    tone                 VARCHAR(16),                        -- danger/normal/temporary/warning（展示色调）
    area_code            VARCHAR(48),
    risk_level           VARCHAR(16),                        -- 一般/预警/严重/紧急
    status_code          VARCHAR(24),                        -- DRAFT/TO_REVIEW/TO_PUBLISH/EFFECTIVE/DISABLED/MISMATCH
    status_label         VARCHAR(24),
    current_version      VARCHAR(16),                        -- v3.x 业务版本
    approver_name        VARCHAR(64),                        -- Demo 自由文本，正式改 user_code
    effective_at         TIMESTAMPTZ,
    expires_at           TIMESTAMPTZ,
    edge_synced_count    INT          NOT NULL DEFAULT 0,
    edge_total_count     INT          NOT NULL DEFAULT 4,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ,
    lock_version         INT          NOT NULL DEFAULT 0,
    is_deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_safety_fence PRIMARY KEY (id),
    CONSTRAINT uq_fence_code UNIQUE (fence_code)
);
CREATE INDEX idx_fence_area   ON safety.safety_fence (area_code);
CREATE INDEX idx_fence_status ON safety.safety_fence (status_code);

-- 5.2 围栏版本 safety_fence_version（历史不可覆盖；polygon 用 JSONB）
CREATE TABLE safety.safety_fence_version (
    id              BIGINT       NOT NULL,
    fence_id        BIGINT       NOT NULL,
    version_no      VARCHAR(16)  NOT NULL,                    -- v1.0/v1.1...
    polygon         JSONB        NOT NULL,                    -- [{x,y}] 0-100 画布点（正式 GIS 前的 Demo 坐标）
    team_scope      JSONB        NOT NULL,                    -- ["装卸一班","设备维保班"] 文本数组；
                                                              -- FENCE-003 含“检修班”，未确认前原文保留
    risk_level      VARCHAR(16),
    change_summary  TEXT,
    effective_at    TIMESTAMPTZ,
    created_by_code VARCHAR(32),
    created_by_name VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_fence_version PRIMARY KEY (id),
    CONSTRAINT uq_fence_version UNIQUE (fence_id, version_no),
    CONSTRAINT fk_fence_version_fence FOREIGN KEY (fence_id)
        REFERENCES safety.safety_fence (id)
);
COMMENT ON COLUMN safety.safety_fence_version.polygon IS '多边形点集结构半结构化、随版本整体冻结、无点级查询需求，适合 JSONB；正式 GIS 后可迁移 PostGIS 几何（VERIFY ON OPENGAUSS 空间扩展）';

-- 5.3 围栏边缘同步 safety_fence_edge_sync [P1]
CREATE TABLE safety.safety_fence_edge_sync (
    id              BIGINT       NOT NULL,
    fence_id        BIGINT       NOT NULL,
    version_no      VARCHAR(16)  NOT NULL,
    edge_node_code  VARCHAR(32)  NOT NULL,
    sync_status     VARCHAR(16)  NOT NULL DEFAULT 'pending',  -- pending/syncing/success/failed
    edge_version    VARCHAR(16),
    last_sync_at    TIMESTAMPTZ,
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    CONSTRAINT pk_fence_edge_sync PRIMARY KEY (id),
    CONSTRAINT uq_fence_edge_sync UNIQUE (fence_id, version_no, edge_node_code)
    -- FK: fence_id -> safety_fence(id)；edge_node_code 软引用（边缘资产可能晚于同步记录入库）
);
CREATE INDEX idx_fence_sync_status ON safety.safety_fence_edge_sync (sync_status);

-- =============================================================================
-- 6. 规则配置 [P0 主表/版本；P1 边缘同步]
--    来源：DemoRule（Param/Version/VersionDiff/EdgeNode）、RuleStatuses
-- =============================================================================

-- 6.1 规则主表 safety_rule -----------------------------------------------------
CREATE TABLE safety.safety_rule (
    id                  BIGINT       NOT NULL,
    rule_code           VARCHAR(40)  NOT NULL,               -- RULE-PER-001 / RULE-NEW-001
    rule_name           VARCHAR(160) NOT NULL,
    category            VARCHAR(48),                         -- 人员安全/设备安全...
    rule_type           VARCHAR(48),
    scope               VARCHAR(32),
    risk_level          VARCHAR(16),
    status_code         VARCHAR(24),                         -- DRAFT/REVIEW/APPROVED/PUBLISHING/ACTIVE/MISMATCH/DISABLED
    status_label        VARCHAR(24),
    current_version     VARCHAR(16),
    platform_version    VARCHAR(16),
    owner_user_code     VARCHAR(32),
    owner_name          VARCHAR(64),
    approver_name       VARCHAR(64),
    high_risk           BOOLEAN      DEFAULT FALSE,
    related_modules     JSONB,                               -- String[]
    description         TEXT,
    effective_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    lock_version        INT          NOT NULL DEFAULT 0,
    is_deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_safety_rule PRIMARY KEY (id),
    CONSTRAINT uq_rule_code UNIQUE (rule_code)
);
CREATE INDEX idx_rule_status_category ON safety.safety_rule (status_code, category);

-- 规则适用区域为多值：独立关联表（查询/筛选主轴，不用 JSONB）
CREATE TABLE safety.safety_rule_area (
    id          BIGINT      NOT NULL,
    rule_id     BIGINT      NOT NULL,
    area_code   VARCHAR(48) NOT NULL,                        -- “全部区域”通配在应用层处理，不入关联表
    CONSTRAINT pk_rule_area PRIMARY KEY (id),
    CONSTRAINT uq_rule_area UNIQUE (rule_id, area_code),
    CONSTRAINT fk_rule_area_rule FOREIGN KEY (rule_id)
        REFERENCES safety.safety_rule (id)
);
CREATE INDEX idx_rule_area_area ON safety.safety_rule_area (area_code);

-- 6.2 规则版本 safety_rule_version（支持历史、回滚来源、参数/动作快照）
CREATE TABLE safety.safety_rule_version (
    id               BIGINT       NOT NULL,
    rule_id          BIGINT       NOT NULL,
    version_no       VARCHAR(16)  NOT NULL,
    source_version   VARCHAR(16),                            -- 回滚/派生来源版本
    status_code      VARCHAR(24),
    params           JSONB        NOT NULL,                  -- [{label,value,danger,hint}]
    actions          JSONB        NOT NULL,                   -- ["现场声光提醒",...]
    version_diffs    JSONB,                                   -- [{label,from,to}]
    change_summary   TEXT,
    effective_at     TIMESTAMPTZ,
    created_by_code  VARCHAR(32),
    created_by_name  VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_rule_version PRIMARY KEY (id),
    CONSTRAINT uq_rule_version UNIQUE (rule_id, version_no),
    CONSTRAINT fk_rule_version_rule FOREIGN KEY (rule_id)
        REFERENCES safety.safety_rule (id)
);
COMMENT ON TABLE safety.safety_rule_version IS '回滚语义：把目标版本内容复制为新版本（保留历史不原地改），source_version 记录回滚来源';

-- 6.3 规则边缘同步 safety_rule_edge_sync [P1]
CREATE TABLE safety.safety_rule_edge_sync (
    id                BIGINT       NOT NULL,
    rule_id           BIGINT       NOT NULL,
    version_no        VARCHAR(16)  NOT NULL,
    edge_node_code    VARCHAR(32)  NOT NULL,
    expected_version  VARCHAR(16),
    edge_version      VARCHAR(16),
    sync_status       VARCHAR(16)  NOT NULL DEFAULT 'pending', -- synced/syncing/mismatch/pending/failed
    last_sync_at      TIMESTAMPTZ,
    error_message     TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ,
    CONSTRAINT pk_rule_edge_sync PRIMARY KEY (id),
    CONSTRAINT uq_rule_edge_sync UNIQUE (rule_id, version_no, edge_node_code)
);
CREATE INDEX idx_rule_sync_status ON safety.safety_rule_edge_sync (sync_status);

-- =============================================================================
-- 7. 云边运维 [P1]
--    来源：EdgePendingEvent(+PayloadSummary/EdgeLocalLinkage)、OpsEventLog、RecoveryPhase
-- =============================================================================

-- 7.1 边缘待补传事件 edge_pending_event
CREATE TABLE safety.edge_pending_event (
    id                          UUID         NOT NULL,        -- 应用/边缘生成（离线可生成）
    event_id                    VARCHAR(64)  NOT NULL,        -- 边缘本地事件 ID
    edge_node_code              VARCHAR(32)  NOT NULL,
    event_type                  VARCHAR(48)  NOT NULL,
    business_key                VARCHAR(128),
    idempotency_key             VARCHAR(160) NOT NULL,
    risk_label                  VARCHAR(16),
    payload_summary             JSONB,                        -- {title,area,person,fence,device,risk,businessId,detail}
    local_linkage               JSONB,                        -- EdgeLocalLinkage {alarm,screen,localVoice,localLight,plcStop,actions[]}
    status_code                 VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING/SYNCING/SYNCED/FAILED/DUPLICATE
    retry_count                 INT          NOT NULL DEFAULT 0,
    rule_version_used           VARCHAR(24),
    clock_offset_at_occurrence  BIGINT,
    edge_occurred_at            TIMESTAMPTZ  NOT NULL,        -- 边缘时钟（冻结，不回改）
    server_received_at          TIMESTAMPTZ,                  -- 中心首次接收
    last_retry_at               TIMESTAMPTZ,
    synced_at                   TIMESTAMPTZ,
    sync_delay_sec              BIGINT,
    last_error                  TEXT,
    linked_alert_id             UUID,                         -- 补传后生成的 Alert
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ,
    CONSTRAINT pk_edge_pending_event PRIMARY KEY (id),
    CONSTRAINT uq_edge_event_id UNIQUE (edge_node_code, event_id),
    CONSTRAINT uq_edge_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_pending_status CHECK (status_code IN
        ('PENDING','SYNCING','SYNCED','FAILED','DUPLICATE'))
);
CREATE INDEX idx_edge_pending_node_status
    ON safety.edge_pending_event (edge_node_code, status_code);
CREATE INDEX idx_edge_pending_occurred
    ON safety.edge_pending_event (edge_node_code, edge_occurred_at);
COMMENT ON TABLE safety.edge_pending_event IS '离线期间只入本表，不写 safety_alert、不广播 alert.new；补传在同一事务内建 Alert 并回写 linked_alert_id';

-- 7.2 运维事件日志 ops_event_log（append-only，与 Alert Timeline 不合表）
CREATE TABLE safety.ops_event_log (
    id              BIGINT       NOT NULL,
    node_code       VARCHAR(32),
    event_type      VARCHAR(48)  NOT NULL,    -- NODE_OFFLINE/RECONNECT/CLOCK_*/RULE_*/LOCAL_JUDGEMENT/REPLAY_*/DUPLICATE_SKIPPED/...
    level_code      VARCHAR(16)  NOT NULL,    -- INFO/WARN/ERROR
    message         TEXT,
    details         JSONB,
    trace_id        VARCHAR(64),
    occurred_at     TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ops_event_log PRIMARY KEY (id)
);
CREATE INDEX idx_ops_log_node_time ON safety.ops_event_log (node_code, occurred_at DESC);
CREATE INDEX idx_ops_log_type_time ON safety.ops_event_log (event_type, occurred_at DESC);
COMMENT ON TABLE safety.ops_event_log IS '运维事件流（内存版仅保留最近 N 条；落库后按保留周期归档，不与业务审计时间线混表）';

-- =============================================================================
-- 8. 平台支撑表 [P1/P2]
-- =============================================================================

-- 8.1 事务发件箱 outbox_event [P1，生产推荐；Demo 可暂不启用]
CREATE TABLE safety.outbox_event (
    id              BIGINT       NOT NULL,
    aggregate_type  VARCHAR(32)  NOT NULL,        -- alert/ai_event/fence/rule/edge...
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(48)  NOT NULL,        -- 对应 LiveEventTypes（alert.changed / ai.new / ...）
    payload         JSONB        NOT NULL,        -- 轻量摘要（与 WS 载荷一致，不放完整对象）
    trace_id        VARCHAR(64),
    status_code     VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING/PUBLISHED/FAILED
    retry_count     INT          NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id)
);
CREATE INDEX idx_outbox_status_created ON safety.outbox_event (status_code, created_at);
COMMENT ON TABLE safety.outbox_event IS '生产：业务事务内写业务表+outbox，提交后由投递器转 RocketMQ/WS，杜绝“先广播后回滚”';

-- 8.2 附件引用 safety_attachment [P1]（照片/截图/视频只存引用，绝不存 BLOB）
CREATE TABLE safety.safety_attachment (
    id              BIGINT       NOT NULL,
    resource_id     VARCHAR(64)  NOT NULL,        -- 业务对象 ID（alert_id / treatment_id / ai_event_id）
    resource_type   VARCHAR(24)  NOT NULL,        -- alert_treatment/ai_evidence/...
    storage_key     VARCHAR(255) NOT NULL,        -- 对象存储 key
    access_uri      VARCHAR(512),                -- 有时效的访问路径/签名 URL 引用（不长期存签名）
    file_name       VARCHAR(255),
    content_type    VARCHAR(64),
    size_bytes      BIGINT,
    sha256          VARCHAR(64),
    metadata        JSONB,
    created_by_code VARCHAR(32),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    is_deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_safety_attachment PRIMARY KEY (id)
);
CREATE INDEX idx_attachment_resource ON safety.safety_attachment (resource_type, resource_id);

-- 8.3 通用枚举字典 sys_dictionary / sys_dictionary_item [P1，可选]
--     仅承载“纯 UI 枚举”（事件类型、状态中文映射、联动步骤名等）；
--     User/Team/Area/Camera 等有业务关系的实体走各自主数据表，不塞进字典。
CREATE TABLE safety.sys_dictionary (
    id            BIGINT       NOT NULL,
    dict_code     VARCHAR(48)  NOT NULL,         -- alert_status / risk_level / ai_status ...
    dict_name     VARCHAR(96)  NOT NULL,
    description   VARCHAR(255),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    CONSTRAINT pk_sys_dictionary PRIMARY KEY (id),
    CONSTRAINT uq_sys_dictionary_code UNIQUE (dict_code)
);
CREATE TABLE safety.sys_dictionary_item (
    id            BIGINT       NOT NULL,
    dict_id       BIGINT       NOT NULL,
    item_code     VARCHAR(64)  NOT NULL,         -- 稳定机器 code（PENDING_CONFIRM / URGENT ...）
    item_label    VARCHAR(96)  NOT NULL,         -- 中文展示
    sort_no       INT          NOT NULL DEFAULT 0,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    extra         JSONB,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    CONSTRAINT pk_sys_dictionary_item PRIMARY KEY (id),
    CONSTRAINT uq_sys_dict_item UNIQUE (dict_id, item_code),
    CONSTRAINT fk_dict_item_dict FOREIGN KEY (dict_id)
        REFERENCES safety.sys_dictionary (id)
);

-- 8.4 长期幂等记录 sys_idempotency_record [P2，可选]
--     短期请求幂等以 Kvrocks（Redis 协议，TTL 600s）为主；
--     仅当需要跨 TTL 的长期审计/对账（如边缘补传窗口 > TTL）时启用本表。
CREATE TABLE safety.sys_idempotency_record (
    id                BIGINT       NOT NULL,
    idempotency_key   VARCHAR(160) NOT NULL,
    request_scope     VARCHAR(48),               -- 例如 alert.assign / edge.replay
    request_hash      VARCHAR(128),              -- 同键不同负载冲突检测（409）
    status_code       VARCHAR(16)  NOT NULL DEFAULT 'PROCESSING', -- PROCESSING/DONE/FAILED
    response_code     INT,
    response_summary  TEXT,
    expires_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    CONSTRAINT pk_sys_idempotency PRIMARY KEY (id),
    CONSTRAINT uq_sys_idempotency_key UNIQUE (idempotency_key)
);
CREATE INDEX idx_idempotency_expires ON safety.sys_idempotency_record (expires_at);

-- =============================================================================
-- 9. [P2] 高频/时序数据边界草案 —— 正式规模优先 openGemini
--     下列表仅给出“若一期必须落 openGauss”的最小结构；
--     生产部署存在 openGemini 时，高频写入应走时序库，openGauss 只保留关键事件/状态变化。
--   *** 本区块在 SQL 中以注释形式保留，避免被误当成一期落地对象 ***
-- -----------------------------------------------------------------------------
-- 9.1 人员轨迹 safety_personnel_track（openGemini 候选；openGauss 仅做低频/关键轨迹）
-- CREATE TABLE safety.safety_personnel_track (
--     id            BIGINT      NOT NULL,
--     personnel_id  BIGINT      NOT NULL,      -- FK safety_personnel(id)
--     area_code     VARCHAR(48),
--     pos_x         NUMERIC(8,3),
--     pos_y         NUMERIC(8,3),
--     quality       VARCHAR(24),
--     source        VARCHAR(24),               -- bracelet/uwb/gps
--     recorded_at   TIMESTAMPTZ NOT NULL
-- );
-- 唯一/主索引：(personnel_id, recorded_at DESC)；按时间分区（VERIFY ON OPENGAUSS 分区表）。
-- 当前 Demo /personnel/{id}/track 为确定性即时生成，不落任何存储。
--
-- 9.2 边缘指标 edge_node_metric（openGemini 主责）
-- 列：node_code, cpu_usage_pct, memory_usage_pct, latency_ms, temperature_c, queue_depth, recorded_at
--
-- 9.3 碰撞测量 collision_measurement（openGemini 主责；openGauss 只存风险状态变化/告警关联）
-- 列：device_code, related_device_code, distance, rel_speed, direction, radar_quality,
--     risk_label, plc_status, recorded_at；关键事件可冗余写入 safety_alert_evidence(payload)。
--     风险会话（risk episode）聚合若需要，另设计 collision_risk_episode（P2 待评审）。
-- =============================================================================

-- =============================================================================
-- 10. 序列（BIGINT 代理键）
--     openGauss 兼容 BIGSERIAL/SEQUENCE；为显式可控，这里给出独立 SEQUENCE 风格。
--     VERIFY ON OPENGAUSS: openGauss 6.x 对 BIGSERIAL、GENERATED ALWAYS AS IDENTITY、
--     CREATE SEQUENCE 的支持差异；一期建议统一 BIGINT + 显式 SEQUENCE（或由持久层
--     用雪花/号段分配），事件表 UUID 不依赖序列。
-- -----------------------------------------------------------------------------
-- 示例（其余主数据表同模式，落地时由迁移工具统一生成）：
-- CREATE SEQUENCE safety.seq_sys_team       START 1 INCREMENT 1;
-- CREATE SEQUENCE safety.seq_safety_area    START 1 INCREMENT 1;
-- CREATE SEQUENCE safety.seq_sys_user       START 1 INCREMENT 1;
-- CREATE SEQUENCE safety.seq_safety_alert_timeline START 1 INCREMENT 1;
-- ...（每张 BIGINT 主键表一个序列；alert/ai_event/edge_pending_event/outbox 除外：
--      outbox 高并发写入建议 BIGINT 序列或号段，见 design §主键策略）
-- =============================================================================

-- *** END OF DESIGN DRAFT — NOTHING HERE HAS BEEN EXECUTED ***
