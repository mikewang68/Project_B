-- =============================================================================
-- FINAL DESIGN DRAFT (POST-PATCH REVISION)
-- NOT YET VERIFIED ON OPENGAUSS
-- DO NOT APPLY TO PRODUCTION
-- NEXT STEP: COMPATIBILITY SPIKE
-- =============================================================================
-- 智慧货场 S3「装卸作业安全卡控系统」
-- Backend (Spring Boot 3.5.5 / Java 17) -> openGauss 6.x 持久化最终设计（Final DDL Draft）
--
-- 核心审计输入与演进背景：
--   1. Phase A：状态与风险全面使用稳定机器 Code（NORMAL/WARNING/SEVERE/URGENT 等），
--      废除中文字面量作为业务判断键；责任人全面切换为 user_code（USR-*）+ 姓名快照。
--   2. Phase B：确立以聚合根为单位的完整保存语义（Aggregate Root Save），
--      Service 仅通过聚合根仓储操作子表；Edge 补传以队列仓储为判重权威。
--   3. Phase B.5 (最高优先级最终代码事实)：
--      - B5-01: Alert 升级为风险等级提升（risk_level_code 跃迁），不改变主状态（status_code），
--        主状态集彻底移除 ESCALATED；新增 previous_risk_level_code 记录升级历史。
--      - B5-02: Collision 的 activeAlertId 仅为运行态缓存镜像，非数据库强关系，且由于单设备可参与多Pair，
--        设备表彻底删除 active_alert_id 字段，去重与告警权威完全收归 safety_alert。
--      - B5-03: 事务与发布边界由单一顶层编排收口，发布通过 afterCommit / Outbox 触发。
--      - B5-04: ACTIVE 规则编辑不降级主表状态，生成新版本草稿并下发同步；未发布规则 active_version 允许为 NULL。
--      - B5-05: 业务编号正式多实例单调唯一由 openGauss 发号表/序列接管，表上加 UNIQUE 索引；离线补传队列加 payload_hash。
--
-- 物理设计统计与约束（SQL Parser 实际扫描核验）：
--   * Core Active Tables（核心业务活动表）：24 张。
--     (主数据 3 + 人员设备 4 + 告警聚合 6 + AI聚合 2 + 围栏聚合 3 + 规则聚合 4 + 边缘运维 2 = 24)
--   * Platform Auxiliary Tables（平台发号辅助表）：1 张（sys_business_number）。
--   * Total CREATE TABLE Statements：25 张。
--   * Sequences（物理 BIGINT 主键序列）：21 个，全部通过 DEFAULT nextval(...) 1:1 绑定到对应主键列，无孤立序列。
--   * UUID 聚合根主键：3 张表（safety_alert, ai_event, edge_pending_event）应用层生成，兼顾离线自治与多实例并发。
--   * Deferred Tables（延期表：字典、HTTP幂等、附件、Outbox）：共 5 张，仅以注释记录，不进活动 DDL。
--   * 高频时序（人员轨迹、碰撞测量、边缘指标）：严格划归 openGemini，禁止回流 openGauss。
--   * 禁止破坏性语句：0 DROP TABLE / 0 TRUNCATE / 0 DELETE。
--   * 所有未在 openGauss 实机验证的语法统一标注 -- VERIFY ON OPENGAUSS:
-- =============================================================================

-- =============================================================================
-- 1. Schema 定义
-- =============================================================================
-- VERIFY ON OPENGAUSS: 确认 safety schema 的创建权限及 search_path 配置规范
CREATE SCHEMA IF NOT EXISTS safety;

-- =============================================================================
-- 2. 物理主键序列定义（Sequences for 21 BIGINT PKs）
--    必须在表创建前声明，以便 DEFAULT nextval(...) 能够成功绑定
-- =============================================================================
-- VERIFY ON OPENGAUSS: 确认独立 SEQUENCE 语法及性能行为（START WITH 1000 INCREMENT BY 1）
CREATE SEQUENCE IF NOT EXISTS safety.seq_sys_team START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_safety_area START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_sys_user START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_safety_personnel START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_device_camera START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_collision_device START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_edge_node START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_safety_alert_timeline START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_safety_alert_evidence START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_safety_alert_treatment START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_safety_alert_linkage START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_safety_alert_linkage_step START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_ai_event_timeline START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_safety_fence START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_safety_fence_version START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_safety_fence_edge_sync START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_safety_rule START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_safety_rule_area START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_safety_rule_version START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_safety_rule_edge_sync START WITH 1000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS safety.seq_ops_event_log START WITH 1000 INCREMENT BY 1;

-- =============================================================================
-- 3. 主数据 / 组织区域（Master Data，3 张表）—— 全部 [P0]
-- =============================================================================

-- 3.1 班组主数据 sys_team ------------------------------------------------------
CREATE TABLE safety.sys_team (
    id              BIGINT       NOT NULL DEFAULT nextval('safety.seq_sys_team'), -- VERIFY ON OPENGAUSS: 确认 DEFAULT nextval 绑定及驱动 RETURNING 行为
    team_code       VARCHAR(48)  NOT NULL,                    -- 业务主键：LOADING_TEAM_1 等
    team_name       VARCHAR(64)  NOT NULL,                    -- 班组名称
    team_type       VARCHAR(24)  NOT NULL DEFAULT 'INTERNAL',  -- INTERNAL / CONTRACTOR / QUALITY
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,       -- 停用不物理删除
    demo_unverified BOOLEAN      NOT NULL DEFAULT FALSE,      -- Demo 数据核验标记
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    CONSTRAINT pk_sys_team PRIMARY KEY (id),
    CONSTRAINT uq_sys_team_code UNIQUE (team_code)
);
COMMENT ON TABLE safety.sys_team IS '班组主数据台账（Demo 6 行）';

-- 3.2 作业区域主数据 safety_area -----------------------------------------------
CREATE TABLE safety.safety_area (
    id              BIGINT       NOT NULL DEFAULT nextval('safety.seq_safety_area'), -- VERIFY ON OPENGAUSS
    area_code       VARCHAR(48)  NOT NULL,                    -- 业务主键：LOADING_AREA_A 等
    area_name       VARCHAR(64)  NOT NULL,                    -- 区域规范名称
    area_type       VARCHAR(32)  NOT NULL DEFAULT 'OPERATION', -- OPERATION/LANE/BLOCK/FACILITY
    parent_id       BIGINT,                                   -- 区域层级（UNRESOLVED 期间一律 NULL）
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    demo_unresolved BOOLEAN      NOT NULL DEFAULT FALSE,      -- 箱区/通道等未确认层级置 TRUE
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    CONSTRAINT pk_safety_area PRIMARY KEY (id),
    CONSTRAINT uq_safety_area_code UNIQUE (area_code),
    CONSTRAINT fk_safety_area_parent FOREIGN KEY (parent_id)
        REFERENCES safety.safety_area (id)
);
COMMENT ON TABLE safety.safety_area IS '作业区域主数据（Demo 12 个，含独立未确定层级区域）';

-- 3.3 系统用户/责任人 sys_user -------------------------------------------------
CREATE TABLE safety.sys_user (
    id                     BIGINT       NOT NULL DEFAULT nextval('safety.seq_sys_user'), -- VERIFY ON OPENGAUSS
    user_code              VARCHAR(32)  NOT NULL,             -- 业务主键：USR-001...USR-007
    user_name              VARCHAR(64)  NOT NULL,             -- 真实姓名
    team_code              VARCHAR(48),                       -- 所属班组软引用（外部 IAM 时可空）
    role_code              VARCHAR(48),                       -- 角色机器 Code（SAFETY_OFFICER 等）
    role_name_snapshot     VARCHAR(64),                       -- 角色名称快照
    shift_code             VARCHAR(24),                       -- 班次 Code
    enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
    demo_unverified        BOOLEAN      NOT NULL DEFAULT FALSE,
    is_deleted             BOOLEAN      NOT NULL DEFAULT FALSE, -- 软删除标记
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ,
    CONSTRAINT pk_sys_user PRIMARY KEY (id),
    CONSTRAINT uq_sys_user_code UNIQUE (user_code)
);
COMMENT ON TABLE safety.sys_user IS '系统用户与责任人台账（不存密码/Token，供派单、审批、复核软引用）';

-- =============================================================================
-- 4. 现场人员与设备资产（Field Personnel & Devices，4 张表）—— [P0 / P1]
-- =============================================================================

-- 4.1 现场作业人员 safety_personnel -------------------------------------------
-- 区分：在岗状态(online_status_code)、手环状态(bracelet_status_code)、业务风险(person_risk_code)
-- 明确：person_state、distance_today、alerts_today 均为派生字段，不作为物理权威
CREATE TABLE safety.safety_personnel (
    id                   BIGINT       NOT NULL DEFAULT nextval('safety.seq_safety_personnel'), -- VERIFY ON OPENGAUSS
    personnel_code       VARCHAR(32)  NOT NULL,               -- 业务编码：P-ZHAO, P-002...
    job_no               VARCHAR(32),                         -- 工号（P-24018，外协 V-10012）
    person_name          VARCHAR(64)  NOT NULL,
    team_code            VARCHAR(48),                         -- 所属班组软引用
    area_code            VARCHAR(48),                         -- 当前所在区域软引用
    bracelet_code        VARCHAR(32),                         -- 佩戴手环终端编号（WB-018）
    battery_pct          SMALLINT,                            -- 手环电量百分比 0-100
    online_status_code   VARCHAR(16)  NOT NULL DEFAULT 'ONLINE', -- PersonStatuses: ONLINE / OFFLINE
    bracelet_status_code VARCHAR(24)  NOT NULL DEFAULT 'ONLINE', -- BraceletStatuses: ONLINE / OFFLINE / LOW_BATTERY
    person_risk_code     VARCHAR(16)  NOT NULL DEFAULT 'NORMAL', -- PersonRiskLevels: NORMAL / ATTENTION / HIGH
    positioning_quality  VARCHAR(24),                         -- 定位质量感知值（优秀/良好/较低/无信号）
    pos_x                NUMERIC(8,3),                        -- 画布相对百分比坐标 X (0-100)
    pos_y                NUMERIC(8,3),                        -- 画布相对百分比坐标 Y (0-100)
    coordinate_text      VARCHAR(64),                         -- 坐标文本快照
    linked_user_code     VARCHAR(32),                         -- 预留未来关联系统账号（软引用，无强 FK）
    last_seen_at         TIMESTAMPTZ,                         -- 最后上报时间戳
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    is_deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ,
    CONSTRAINT pk_safety_personnel PRIMARY KEY (id),
    CONSTRAINT uq_safety_personnel_code UNIQUE (personnel_code),
    CONSTRAINT uq_safety_personnel_job UNIQUE (job_no)
    -- VERIFY ON OPENGAUSS: CHECK (battery_pct >= 0 AND battery_pct <= 100)
);
COMMENT ON TABLE safety.safety_personnel IS '现场作业人员台账与最新态势（高频连续轨迹归 openGemini）';

-- 4.2 摄像头台账 device_camera -------------------------------------------------
CREATE TABLE safety.device_camera (
    id                 BIGINT       NOT NULL DEFAULT nextval('safety.seq_device_camera'), -- VERIFY ON OPENGAUSS
    camera_code        VARCHAR(48)  NOT NULL,                 -- 编号：CAM-01...CAM-08
    camera_name        VARCHAR(96)  NOT NULL,
    area_code          VARCHAR(48),                           -- 所属区域软引用
    rtsp_url           VARCHAR(256),                          -- 视频流地址（不含明文认证）
    stream_type        VARCHAR(24)  NOT NULL DEFAULT 'MAIN',  -- MAIN / SUB
    health_code        VARCHAR(24)  NOT NULL DEFAULT 'NORMAL', -- NORMAL / DEGRADED / OFFLINE
    ptz_supported      BOOLEAN      NOT NULL DEFAULT FALSE,
    ai_enabled         BOOLEAN      NOT NULL DEFAULT TRUE,    -- 是否接入 AI 检测
    assigned_edge_code VARCHAR(32),                           -- 负责分析的边缘节点软引用
    pos_x              NUMERIC(8,3),                          -- 地图百分比坐标
    pos_y              NUMERIC(8,3),
    installed_at       TIMESTAMPTZ,
    last_heartbeat_at  TIMESTAMPTZ,
    is_deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ,
    CONSTRAINT pk_device_camera PRIMARY KEY (id),
    CONSTRAINT uq_device_camera_code UNIQUE (camera_code)
);
COMMENT ON TABLE safety.device_camera IS '监控摄像头台账与分析关联';

-- 4.3 防碰撞设备台账 collision_device ------------------------------------------
-- 明确：risk_level_code 与 sensor_health_code 正交拆分，不得将 UNCERTAIN 混入风险等级
-- 核心修正 (Patch 4 / B5-02)：
--   彻底删除 active_alert_id / latest_alert_id 字段！
--   原因：单设备可同时参与多个 Pair（如 VEH-07 与 TIP-02、CRANE-01 同时靠近），
--   设备行上的单一 active_alert_id 存在基数错误（1:N 无法存入 1:1 标量列）。
--   去重权威与当前告警查询完全基于 safety_alert 中未关闭的 dedup_key（COLLISION:<deviceA>:<deviceB>）。
CREATE TABLE safety.collision_device (
    id                   BIGINT       NOT NULL DEFAULT nextval('safety.seq_collision_device'), -- VERIFY ON OPENGAUSS
    device_code          VARCHAR(48)  NOT NULL,               -- VEH-07, TIP-02, CRANE-01...
    device_name          VARCHAR(64)  NOT NULL,
    device_type          VARCHAR(32)  NOT NULL,               -- 转运车辆 / 翻箱机 / 龙门吊
    area_code            VARCHAR(48),                         -- 所属区域软引用
    status_code          VARCHAR(24)  NOT NULL DEFAULT 'NORMAL', -- 运行状态
    speed                NUMERIC(6,2),                        -- 当前速度 m/s
    direction            VARCHAR(32),                         -- 运行方向
    control_status       VARCHAR(24),                         -- 控制状态
    communication_status VARCHAR(24),                         -- 通信状态
    radar_status         VARCHAR(24),                         -- 雷达通信状态
    risk_level_code      VARCHAR(16)  NOT NULL DEFAULT 'SAFE', -- CollisionRiskLevels: SAFE/WARNING/SEVERE/URGENT
    sensor_health_code   VARCHAR(24)  NOT NULL DEFAULT 'NORMAL', -- SensorHealth: NORMAL/UNCERTAIN/RADAR_DOWN
    related_device_code  VARCHAR(48),                         -- 最近配对设备（运行态缓存）
    pos_x                NUMERIC(8,3),                        -- 地图百分比坐标
    pos_y                NUMERIC(8,3),
    last_updated_at      TIMESTAMPTZ,
    is_deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ,
    CONSTRAINT pk_collision_device PRIMARY KEY (id),
    CONSTRAINT uq_collision_device_code UNIQUE (device_code)
);
COMMENT ON TABLE safety.collision_device IS '防碰撞重点受控设备台账与最新态势（高频毫米波测距归 openGemini，告警查询走 Alert dedupKey）';

-- 4.4 边缘节点台账 edge_node ---------------------------------------------------
CREATE TABLE safety.edge_node (
    id                    BIGINT       NOT NULL DEFAULT nextval('safety.seq_edge_node'), -- VERIFY ON OPENGAUSS
    node_code             VARCHAR(32)  NOT NULL,              -- EDGE-01...EDGE-04
    node_name             VARCHAR(96)  NOT NULL,
    area_code             VARCHAR(48),
    ip_address            VARCHAR(45),
    node_status_code      VARCHAR(16)  NOT NULL DEFAULT 'ONLINE', -- ONLINE/DEGRADED/OFFLINE/RECOVERING/ERROR
    cloud_connected       BOOLEAN      NOT NULL DEFAULT TRUE,
    autonomy_active       BOOLEAN      NOT NULL DEFAULT FALSE, -- 自治激活标记
    agent_version         VARCHAR(32),                        -- 部署的 Agent 版本
    active_rule_version   VARCHAR(24),                        -- 当前运行的规则版本
    expected_rule_version VARCHAR(24),                        -- 平台期望生效版本
    active_fence_version  VARCHAR(24),                        -- 当前运行的围栏版本
    expected_fence_version VARCHAR(24),                       -- 平台期望围栏版本
    clock_offset_ms       BIGINT,                             -- 时钟偏移毫秒（Demo 模拟）
    latency_ms            INT,                                -- 网络延迟
    cpu_usage_pct         SMALLINT,                           -- 最新 CPU 占比（历史归 openGemini）
    memory_usage_pct      SMALLINT,                           -- 最新内存占比
    disk_usage_pct        SMALLINT,                           -- 最新磁盘占比
    temperature_c         NUMERIC(5,2),                       -- 最新温度
    queue_depth           INT          NOT NULL DEFAULT 0,    -- 离线队列待补传事件数
    cached_event_count    INT          NOT NULL DEFAULT 0,    -- 累计缓存数
    uptime_sec            BIGINT,
    last_heartbeat_at     TIMESTAMPTZ,
    last_sync_at          TIMESTAMPTZ,
    last_error            TEXT,
    is_deleted            BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ,
    CONSTRAINT pk_edge_node PRIMARY KEY (id),
    CONSTRAINT uq_edge_node_code UNIQUE (node_code)
);
COMMENT ON TABLE safety.edge_node IS '边缘计算节点台账与运行态摘要（高频性能指标归 openGemini）';

-- =============================================================================
-- 5. 告警处置主聚合（Alert Aggregate Cluster，6 张表）—— [P0]
--    持久化单元：AlertRepository.save 对应主表 + 子表明细的级联事务写
-- =============================================================================

-- 5.1 告警主表 safety_alert ----------------------------------------------------
-- 关键变更：
-- 1. status_code 彻底移除 ESCALATED；升级为 risk_level_code 跃迁
-- 2. previous_risk_level_code 记录升级前风险，废除旧 upgraded_from_alert_no
-- 3. 增加决策来源血缘：decision_source_type/code/version
-- 4. 责任人正式采用 assignee_user_code + 姓名快照
-- 5. rule_code 允许可空（人员越界/碰撞/AI 无需伪造规则代码）
CREATE TABLE safety.safety_alert (
    id                        UUID         NOT NULL,          -- 应用层生成 UUID（离线/多实例兼容）
    alert_no                  VARCHAR(40)  NOT NULL,          -- 业务编号：ALM-yyyyMMdd-NNN
    title                     VARCHAR(200) NOT NULL,
    source                    VARCHAR(32),                    -- 人员安全/设备防碰撞/AI违规等
    event_type                VARCHAR(48),                    -- 细分业务事件类型
    risk_level_code           VARCHAR(16)  NOT NULL,          -- RiskLevels: NORMAL/WARNING/SEVERE/URGENT
    status_code               VARCHAR(24)  NOT NULL,          -- AlertStatuses: PENDING_CONFIRM..CLOSED (无 ESCALATED)
    previous_risk_level_code  VARCHAR(16),                    -- B5-01 升级前风险等级机器 Code
    upgraded_from_code        VARCHAR(16),                    -- 历史首次升级来源等级
    mobile_stage_code         VARCHAR(24),                    -- DENORMALIZED CURRENT WORKFLOW CACHE (PENDING/ACCEPTED/ARRIVED/PROCESSING)
    priority                  VARCHAR(16),                    -- 普通 / 紧急
    area_code                 VARCHAR(48),                    -- 区域软引用
    area_name_snapshot        VARCHAR(64),                    -- 发生时区域名称快照
    target_object_type        VARCHAR(32),                    -- 人员 / 设备 / 围栏
    target_object_id          VARCHAR(48),                    -- 目标对象标识
    target_object_name_snapshot VARCHAR(64),
    assignee_user_code        VARCHAR(32),                    -- 责任人系统账号 code (USR-*)
    assignee_name_snapshot    VARCHAR(64),                    -- 责任人姓名快照
    confirm_user_code         VARCHAR(32),
    confirm_user_name_snapshot VARCHAR(64),
    review_user_code          VARCHAR(32),
    review_user_name_snapshot VARCHAR(64),
    review_note               TEXT,
    decision_source_type      VARCHAR(32),                    -- DecisionSources: FENCE/RULE/AI_MODEL/COLLISION/EDGE/MANUAL
    decision_source_code      VARCHAR(64),                    -- 决策源实体编号（如 FENCE-003 / RULE-DEV-003）
    decision_source_version   VARCHAR(32),                    -- 决策源版本（如 v1.2 / v2.4）
    rule_code                 VARCHAR(48),                    -- 规则触发时记录（允许为 NULL）
    rule_version              VARCHAR(24),                    -- 规则触发时记录（允许为 NULL）
    linked_ai_event_id        UUID,                           -- AI 来源溯源软引用（无强外键）
    origin                    VARCHAR(24)  NOT NULL DEFAULT 'CLOUD', -- CLOUD / EDGE_REPLAY
    edge_node_code            VARCHAR(32),                    -- 边缘补传节点编号
    offline_event_id          VARCHAR(64),                    -- 边缘离线事件编号
    rule_version_used         VARCHAR(24),                    -- 边缘判定时生效版本
    offline_occurred          BOOLEAN      DEFAULT FALSE,     -- 是否离线期间发生
    sync_delay_sec            BIGINT,                         -- 补传延迟秒数
    synced_at                 TIMESTAMPTZ,                    -- 补传完成时间戳
    dedup_key                 VARCHAR(160),                   -- 去重特征键（如 COLLISION:VEH-07:TIP-02）
    duration_sec              INT,                            -- 持续时长
    sla_limit_min             INT,                            -- SLA 时限分钟
    sla_deadline              TIMESTAMPTZ,                    -- SLA 截止事实时间戳
    occurred_at               TIMESTAMPTZ  NOT NULL,          -- 事实发生时间戳（边缘事件用边缘时钟）
    detected_at               TIMESTAMPTZ,                    -- 系统检测时间戳
    server_received_at        TIMESTAMPTZ,                    -- 中心服务器接收时间戳
    confirmed_at              TIMESTAMPTZ,                    -- 人工确认时间戳
    accepted_at               TIMESTAMPTZ,                    -- 移动端接单事实时间戳
    arrived_at                TIMESTAMPTZ,                    -- 移动端到场事实时间戳
    closed_at                 TIMESTAMPTZ,                    -- 闭环事实时间戳
    linkage_available         BOOLEAN      DEFAULT FALSE,
    linkage_finished          BOOLEAN      DEFAULT FALSE,
    linkage_failed            BOOLEAN      DEFAULT FALSE,
    takeover                  BOOLEAN      DEFAULT FALSE,
    lock_version              INT          NOT NULL DEFAULT 0, -- 乐观锁版本号
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ,
    CONSTRAINT pk_safety_alert PRIMARY KEY (id),
    CONSTRAINT uq_safety_alert_no UNIQUE (alert_no)
    -- VERIFY ON OPENGAUSS: 检查约束枚举合法性
    -- CHECK (risk_level_code IN ('NORMAL', 'WARNING', 'SEVERE', 'URGENT'))
    -- CHECK (status_code IN ('PENDING_CONFIRM', 'CONFIRMED', 'PENDING_ASSIGNMENT', 'PENDING_PROCESS', 'PROCESSING', 'PENDING_REVIEW', 'CLOSED', 'CANCELLED'))
);
COMMENT ON TABLE safety.safety_alert IS '安全卡控告警主表（全系统唯一处置主链实体）';

-- 5.2 告警时间线 safety_alert_timeline -----------------------------------------
-- 审计流、append-only，不可更新删除；按 sequence_no 严格排序
CREATE TABLE safety.safety_alert_timeline (
    id                     BIGINT       NOT NULL DEFAULT nextval('safety.seq_safety_alert_timeline'), -- VERIFY ON OPENGAUSS
    alert_id               UUID         NOT NULL,
    sequence_no            INT          NOT NULL,             -- 聚合内严格连续序号 1, 2, 3...
    event_type             VARCHAR(48)  NOT NULL,             -- AlertTimelineEventTypes 机器 Code
    state_code             VARCHAR(16)  NOT NULL DEFAULT 'done', -- done / active
    status_after           VARCHAR(24),                       -- 流转后告警主状态
    operator_user_code     VARCHAR(32),                       -- 操作人用户 Code
    operator_name_snapshot VARCHAR(64),                       -- 操作人姓名快照
    message                TEXT,                              -- 节点描述信息
    metadata               JSONB,                             -- 扩展结构化审计明细
    trace_id               VARCHAR(64),                       -- 请求全链路追踪 ID
    occurred_at            TIMESTAMPTZ  NOT NULL,             -- 动作执行事实时间戳
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_safety_alert_timeline PRIMARY KEY (id),
    CONSTRAINT uq_safety_alert_timeline_seq UNIQUE (alert_id, sequence_no),
    CONSTRAINT fk_safety_alert_timeline_alert FOREIGN KEY (alert_id)
        REFERENCES safety.safety_alert (id) ON DELETE CASCADE
);
COMMENT ON TABLE safety.safety_alert_timeline IS '告警流转审计时间线（Append-only，由 sequence_no 保序）';

-- 5.3 告警多态证据快照 safety_alert_evidence ------------------------------------
-- 单表 + JSONB payload 存储 5 类 sealed union 证据（人员越界/设备碰撞/AI检测/指标异常等）
CREATE TABLE safety.safety_alert_evidence (
    id            BIGINT       NOT NULL DEFAULT nextval('safety.seq_safety_alert_evidence'), -- VERIFY ON OPENGAUSS
    alert_id      UUID         NOT NULL,
    evidence_type VARCHAR(32)  NOT NULL,                      -- PERSONNEL / COLLISION / AI / METRIC
    source_code   VARCHAR(64),                                -- 证据来源硬件/模型 Code
    occurred_at   TIMESTAMPTZ,                                -- 证据采样时间戳
    payload       JSONB        NOT NULL,                      -- 结构化轨迹点集/检测框/测距数据
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_safety_alert_evidence PRIMARY KEY (id),
    CONSTRAINT fk_safety_alert_evidence_alert FOREIGN KEY (alert_id)
        REFERENCES safety.safety_alert (id) ON DELETE CASCADE
);
COMMENT ON TABLE safety.safety_alert_evidence IS '告警多态结构化证据快照（单表 + JSONB Payload）';

-- 5.4 告警处置记录 safety_alert_treatment ---------------------------------------
CREATE TABLE safety.safety_alert_treatment (
    id                     BIGINT       NOT NULL DEFAULT nextval('safety.seq_safety_alert_treatment'), -- VERIFY ON OPENGAUSS
    alert_id               UUID         NOT NULL,
    measures               JSONB,                             -- 采取的防范/整改措施列表
    summary                TEXT,                              -- 处置结论文字汇总
    operator_user_code     VARCHAR(32),                       -- 处置人 Code
    operator_name_snapshot VARCHAR(64),                       -- 处置人姓名快照
    attachments            JSONB,                             -- 关联附件元数据数组
    submitted_at           TIMESTAMPTZ  NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_safety_alert_treatment PRIMARY KEY (id),
    CONSTRAINT fk_safety_alert_treatment_alert FOREIGN KEY (alert_id)
        REFERENCES safety.safety_alert (id) ON DELETE CASCADE
);
COMMENT ON TABLE safety.safety_alert_treatment IS '现场处置执行记录（复核驳回可有多条）';

-- 5.5 告警现场联动执行会话 safety_alert_linkage --------------------------------
CREATE TABLE safety.safety_alert_linkage (
    id            BIGINT       NOT NULL DEFAULT nextval('safety.seq_safety_alert_linkage'), -- VERIFY ON OPENGAUSS
    alert_id      UUID         NOT NULL,
    status_code   VARCHAR(24)  NOT NULL DEFAULT 'STARTED',    -- STARTED / FINISHED / FAILED / TAKEOVER
    started_at    TIMESTAMPTZ  NOT NULL,
    finished_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_safety_alert_linkage PRIMARY KEY (id),
    CONSTRAINT fk_safety_alert_linkage_alert FOREIGN KEY (alert_id)
        REFERENCES safety.safety_alert (id) ON DELETE CASCADE
);
COMMENT ON TABLE safety.safety_alert_linkage IS '现场声光/PLC控制联动会话主表';

-- 5.6 联动执行步骤明细 safety_alert_linkage_step ------------------------------
CREATE TABLE safety.safety_alert_linkage_step (
    id          BIGINT       NOT NULL DEFAULT nextval('safety.seq_safety_alert_linkage_step'), -- VERIFY ON OPENGAUSS
    linkage_id  BIGINT       NOT NULL,
    step_no     INT          NOT NULL,                        -- 步骤次序 1..6
    step_name   VARCHAR(64)  NOT NULL,                        -- 检测告警/声光提醒/通知司机/请求减速/PLC停机/人工接管
    status_code VARCHAR(24)  NOT NULL,                        -- PENDING / SUCCESS / FAILED / SKIPPED
    action_text VARCHAR(128),                                 -- 具体执行指令摘要
    executed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_safety_alert_linkage_step PRIMARY KEY (id),
    CONSTRAINT uq_safety_alert_linkage_step_no UNIQUE (linkage_id, step_no),
    CONSTRAINT fk_safety_alert_linkage_step_linkage FOREIGN KEY (linkage_id)
        REFERENCES safety.safety_alert_linkage (id) ON DELETE CASCADE
);
COMMENT ON TABLE safety.safety_alert_linkage_step IS '联动步骤执行状态记录（权威在 Alert 侧，设备侧仅为回显）';

-- =============================================================================
-- 6. AI 视觉识别事件聚合（AI Aggregate Cluster，2 张表）—— [P0]
--    独立实体：负责识别、置信度分析与复核派单入口；确认后关联写入 Alert 主表
-- =============================================================================

-- 6.1 AI 识别事件主表 ai_event ------------------------------------------------
CREATE TABLE safety.ai_event (
    id                     UUID         NOT NULL,             -- 应用层生成 UUID
    event_no               VARCHAR(40)  NOT NULL,             -- 业务编号：AI-E-yyyyMMdd-NNN
    event_type             VARCHAR(48)  NOT NULL,             -- 未戴安全帽/翻箱机非法作业等
    camera_code            VARCHAR(48),                       -- 关联摄像头软引用
    camera_name_snapshot   VARCHAR(64),
    area_code              VARCHAR(48),
    confidence             NUMERIC(5,4),                      -- 识别置信度 (0.0000 - 1.0000)
    duration_sec           NUMERIC(8,2),                      -- 行为持续时长
    model_code             VARCHAR(64),                       -- 检测模型代号/版本
    threshold              NUMERIC(5,4),                      -- 触发判定阈值
    status_code            VARCHAR(24)  NOT NULL,             -- AiReviewStatuses: PENDING..CLOSED
    risk_code              VARCHAR(16)  NOT NULL,             -- AiRiskLevels: LOW / MEDIUM / HIGH (独立词表)
    camera_health_code     VARCHAR(24),                       -- 识别时摄像头健康态
    scene_type             VARCHAR(32),                       -- 场景分类：helmet/fence/intrusion 等
    detection_boxes        JSONB,                             -- 目标边界框快照数组 [{label,x,y,w,h,prob}]
    rule_code              VARCHAR(48),                       -- 关联触发规则编号
    related_person_code    VARCHAR(32),
    related_device_code    VARCHAR(48),
    judge_text             TEXT,                              -- AI 自诊断判定依据
    reviewer_user_code     VARCHAR(32),                       -- 复核人 Code
    reviewer_name_snapshot VARCHAR(64),
    review_time            TIMESTAMPTZ,
    false_reason           TEXT,                              -- 误报归因说明
    assignee_user_code     VARCHAR(32),                       -- 派单直接责任人
    assignee_name_snapshot VARCHAR(64),
    assignment_priority    VARCHAR(16),
    process_status_code    VARCHAR(24),
    assignment_note        TEXT,
    linked_alert_id        UUID,                              -- 确认违规后生成的 safety_alert.id（权威关联）
    occurred_at            TIMESTAMPTZ  NOT NULL,             -- 检测事实发生时间戳
    updated_at             TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ai_event PRIMARY KEY (id),
    CONSTRAINT uq_ai_event_no UNIQUE (event_no)
    -- VERIFY ON OPENGAUSS:
    -- CHECK (risk_code IN ('LOW', 'MEDIUM', 'HIGH'))
    -- CHECK (status_code IN ('PENDING', 'CONFIRMED', 'FALSE_POSITIVE', 'UNCERTAIN', 'ASSIGNED', 'PROCESSING', 'CLOSED'))
);
COMMENT ON TABLE safety.ai_event IS 'AI 视觉识别事件主表（独立于 Alert 主表）';

-- 6.2 AI 事件流转时间线 ai_event_timeline -------------------------------------
CREATE TABLE safety.ai_event_timeline (
    id                     BIGINT       NOT NULL DEFAULT nextval('safety.seq_ai_event_timeline'), -- VERIFY ON OPENGAUSS
    ai_event_id            UUID         NOT NULL,
    sequence_no            INT          NOT NULL,             -- 严格保序序号 1, 2, 3...
    event_type             VARCHAR(48)  NOT NULL,             -- AiTimelineEventTypes: DETECTED/QUEUED/CONFIRMED...
    state_code             VARCHAR(16)  NOT NULL DEFAULT 'done',
    operator_user_code     VARCHAR(32),
    operator_name_snapshot VARCHAR(64),
    message                TEXT,
    occurred_at            TIMESTAMPTZ  NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ai_event_timeline PRIMARY KEY (id),
    CONSTRAINT uq_ai_event_timeline_seq UNIQUE (ai_event_id, sequence_no),
    CONSTRAINT fk_ai_event_timeline_ai FOREIGN KEY (ai_event_id)
        REFERENCES safety.ai_event (id) ON DELETE CASCADE
);
COMMENT ON TABLE safety.ai_event_timeline IS 'AI 识别与复核流程审计时间线';

-- =============================================================================
-- 7. 电子围栏聚合（Fence Aggregate Cluster，3 张表）—— [P0 / P1]
--    全版本化管理：主表维持当前状态指针，历史版本存子表
-- =============================================================================

-- 7.1 电子围栏主表 safety_fence -----------------------------------------------
CREATE TABLE safety.safety_fence (
    id                     BIGINT       NOT NULL DEFAULT nextval('safety.seq_safety_fence'), -- VERIFY ON OPENGAUSS
    fence_code             VARCHAR(48)  NOT NULL,             -- 业务编号：FENCE-001...
    fence_name             VARCHAR(64)  NOT NULL,
    fence_kind             VARCHAR(32),                       -- 危险区域 / 临时围栏 / 授权区域
    tone                   VARCHAR(24),                       -- danger / warning / normal
    area_code              VARCHAR(48),                       -- 所属作业区域软引用
    active_version         VARCHAR(24),                       -- 当前生效版本号（如 v1.2，未发布时为 NULL）
    status_code            VARCHAR(24)  NOT NULL,             -- FenceStatuses: DRAFT/TO_REVIEW/TO_PUBLISH/EFFECTIVE/DISABLED/MISMATCH
    effective_at           TIMESTAMPTZ,                       -- 当前版本生效起始时间
    expires_at             TIMESTAMPTZ,                       -- 临时围栏失效时间
    approver_user_code     VARCHAR(32),
    approver_name_snapshot VARCHAR(64),
    lock_version           INT          NOT NULL DEFAULT 0,   -- 乐观锁版本号
    is_deleted             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ,
    CONSTRAINT pk_safety_fence PRIMARY KEY (id),
    CONSTRAINT uq_safety_fence_code UNIQUE (fence_code)
);
COMMENT ON TABLE safety.safety_fence IS '电子围栏业务主表（记录当前状态与生效版本主指针）';

-- 7.2 电子围栏版本明细 safety_fence_version ------------------------------------
-- team_scope 包含未决班组（如检修班），使用 JSONB 保留原始结构，不强制主数据外键
CREATE TABLE safety.safety_fence_version (
    id                     BIGINT       NOT NULL DEFAULT nextval('safety.seq_safety_fence_version'), -- VERIFY ON OPENGAUSS
    fence_id               BIGINT       NOT NULL,
    version_no             VARCHAR(24)  NOT NULL,             -- 版本号：v1.0, v1.1...
    risk_level_code        VARCHAR(16)  NOT NULL,             -- 越界告警等级（RiskLevels）
    status_code            VARCHAR(24)  NOT NULL,             -- 该版本的状态
    polygon                JSONB        NOT NULL,             -- 多边形顶点数组 [{x,y}]
    team_scope             JSONB,                             -- 授权/管控班组结构快照 [{teamCode,rawLabel,unresolved}]
    created_by_user_code   VARCHAR(32),
    created_by_name_snapshot VARCHAR(64),
    effective_at           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_safety_fence_version PRIMARY KEY (id),
    CONSTRAINT uq_safety_fence_version_no UNIQUE (fence_id, version_no),
    CONSTRAINT fk_safety_fence_version_fence FOREIGN KEY (fence_id)
        REFERENCES safety.safety_fence (id) ON DELETE CASCADE
);
COMMENT ON TABLE safety.safety_fence_version IS '电子围栏几何形状与参数版本历史快照';

-- 7.3 围栏边缘节点同步表 safety_fence_edge_sync -------------------------------
CREATE TABLE safety.safety_fence_edge_sync (
    id               BIGINT       NOT NULL DEFAULT nextval('safety.seq_safety_fence_edge_sync'), -- VERIFY ON OPENGAUSS
    fence_id         BIGINT       NOT NULL,
    edge_node_code   VARCHAR(32)  NOT NULL,                   -- EDGE-01..04
    expected_version VARCHAR(24)  NOT NULL,                   -- 平台期望版本
    edge_version     VARCHAR(24),                             -- 边缘节点当前运行版本
    sync_status      VARCHAR(24)  NOT NULL,                   -- synced / syncing / mismatch / offline
    synced_at        TIMESTAMPTZ,
    CONSTRAINT pk_safety_fence_edge_sync PRIMARY KEY (id),
    CONSTRAINT uq_safety_fence_edge_sync_node UNIQUE (fence_id, edge_node_code),
    CONSTRAINT fk_safety_fence_edge_sync_fence FOREIGN KEY (fence_id)
        REFERENCES safety.safety_fence (id) ON DELETE CASCADE
);
COMMENT ON TABLE safety.safety_fence_edge_sync IS '电子围栏向各边缘节点的下发与同步对账表';

-- =============================================================================
-- 8. 安全规则配置聚合（Rule Aggregate Cluster，4 张表）—— [P0 / P1]
--    B5-04 核心语义支撑：编辑 ACTIVE 规则不降级主表，版本子表新增草稿
-- =============================================================================

-- 8.1 安全规则主表 safety_rule -------------------------------------------------
-- 语义消歧 (Patch 3 / B5-04)：
--   active_version: 当前Edge/线上实际生效执行的版本。未发布的新建规则为 NULL；编辑时不改变。
--   platform_version: 平台当前编辑态最新版本（如新建时 v1.0，在线编辑生成新草稿时为 v2.5）。
CREATE TABLE safety.safety_rule (
    id                     BIGINT       NOT NULL DEFAULT nextval('safety.seq_safety_rule'), -- VERIFY ON OPENGAUSS
    rule_code              VARCHAR(48)  NOT NULL,             -- 业务编号：RULE-DEV-003, RULE-PER-001...
    rule_name              VARCHAR(96)  NOT NULL,
    category               VARCHAR(32)  NOT NULL,             -- 人员安全 / 设备安全 / 联动策略等
    rule_type              VARCHAR(32),                       -- 细分类型
    scope_desc             VARCHAR(128),                      -- 范围描述
    active_version         VARCHAR(24),                       -- 当前线上实际生效版本（从未发布的草稿为 NULL；编辑 ACTIVE 时不降级）
    platform_version       VARCHAR(24),                       -- 平台编辑态最新版本（如草稿 v1.0 或新草稿 v2.5）
    risk_level_code        VARCHAR(16)  NOT NULL,             -- 触发告警风险等级（RiskLevels）
    status_code            VARCHAR(24)  NOT NULL,             -- RuleStatuses: DRAFT/REVIEW/APPROVED/PUBLISHING/ACTIVE/MISMATCH/DISABLED
    high_risk              BOOLEAN      NOT NULL DEFAULT FALSE, -- 高危规则标识（审批需二次确认）
    owner_user_code        VARCHAR(32),                       -- 规则负责人
    owner_name_snapshot    VARCHAR(64),
    approver_user_code     VARCHAR(32),
    approver_name_snapshot VARCHAR(64),
    effective_at           TIMESTAMPTZ,                       -- 当前生效版本的生效时间戳（编辑不抹除）
    description            TEXT,
    related_modules        JSONB,                             -- 关联业务模块
    lock_version           INT          NOT NULL DEFAULT 0,   -- 乐观锁版本号
    is_deleted             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ,
    CONSTRAINT pk_safety_rule PRIMARY KEY (id),
    CONSTRAINT uq_safety_rule_code UNIQUE (rule_code)
);
COMMENT ON TABLE safety.safety_rule IS '安全卡控规则主表（记录当前线上运行版本与全局主状态，编辑时不降级）';

-- 8.2 规则适用区域关联表 safety_rule_area -------------------------------------
-- 适用区域是核心检索主轴，采用关系表建模，不使用 JSONB
CREATE TABLE safety.safety_rule_area (
    id        BIGINT       NOT NULL DEFAULT nextval('safety.seq_safety_rule_area'), -- VERIFY ON OPENGAUSS
    rule_id   BIGINT       NOT NULL,
    area_code VARCHAR(48)  NOT NULL,                          -- 区域编码（含“全部区域”通配标识）
    CONSTRAINT pk_safety_rule_area PRIMARY KEY (id),
    CONSTRAINT uq_safety_rule_area_unique UNIQUE (rule_id, area_code),
    CONSTRAINT fk_safety_rule_area_rule FOREIGN KEY (rule_id)
        REFERENCES safety.safety_rule (id) ON DELETE CASCADE
);
COMMENT ON TABLE safety.safety_rule_area IS '安全规则适用作业区域多值关联表';

-- 8.3 安全规则版本历史明细 safety_rule_version ---------------------------------
-- B5-04: 普通编辑直接往本表追加新草稿版本（status_code='DRAFT'），发布时才晋升为 ACTIVE
CREATE TABLE safety.safety_rule_version (
    id                   BIGINT       NOT NULL DEFAULT nextval('safety.seq_safety_rule_version'), -- VERIFY ON OPENGAUSS
    rule_id              BIGINT       NOT NULL,
    version_no           VARCHAR(24)  NOT NULL,               -- 版本号：v1.0, v2.4, v2.5...
    status_code          VARCHAR(24)  NOT NULL,               -- DRAFT / REVIEW / APPROVED / ACTIVE / ARCHIVED
    params               JSONB        NOT NULL,               -- 参数阈值结构化快照 [{label,value,danger,hint}]
    actions              JSONB        NOT NULL,               -- 联动动作列表 ["设备停机", "通知司机"]
    source_version       VARCHAR(24),                         -- 回滚/修订的来源版本号
    version_note         TEXT,                                -- 版本变更说明
    version_diffs        JSONB,                               -- 差异摘要
    author_user_code     VARCHAR(32),
    author_name_snapshot VARCHAR(64),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_safety_rule_version PRIMARY KEY (id),
    CONSTRAINT uq_safety_rule_version_no UNIQUE (rule_id, version_no),
    CONSTRAINT fk_safety_rule_version_rule FOREIGN KEY (rule_id)
        REFERENCES safety.safety_rule (id) ON DELETE CASCADE
);
COMMENT ON TABLE safety.safety_rule_version IS '安全规则历史版本与参数配置快照';

-- 8.4 规则边缘节点同步表 safety_rule_edge_sync ---------------------------------
CREATE TABLE safety.safety_rule_edge_sync (
    id               BIGINT       NOT NULL DEFAULT nextval('safety.seq_safety_rule_edge_sync'), -- VERIFY ON OPENGAUSS
    rule_id          BIGINT       NOT NULL,
    edge_node_code   VARCHAR(32)  NOT NULL,                   -- EDGE-01..04
    expected_version VARCHAR(24)  NOT NULL,                   -- 平台期望生效版本号（跟随发布后的目标版本）
    edge_version     VARCHAR(24),                             -- 边缘实际上报版本号
    sync_status      VARCHAR(24)  NOT NULL,                   -- synced / syncing / mismatch / offline
    synced_at        TIMESTAMPTZ,
    CONSTRAINT pk_safety_rule_edge_sync PRIMARY KEY (id),
    CONSTRAINT uq_safety_rule_edge_sync_node UNIQUE (rule_id, edge_node_code),
    CONSTRAINT fk_safety_rule_edge_sync_rule FOREIGN KEY (rule_id)
        REFERENCES safety.safety_rule (id) ON DELETE CASCADE
);
COMMENT ON TABLE safety.safety_rule_edge_sync IS '安全规则边缘节点分发与同步状态表';

-- =============================================================================
-- 9. 边缘离线队列与运维审计（Edge Queue & Ops Logs，2 张表）—— [P1]
-- =============================================================================

-- 9.1 边缘离线补传队列 edge_pending_event --------------------------------------
-- 核心审计：idempotency_key UNIQUE 约束；增加 payload_hash 用于检测 409 载荷冲突
-- 语义统一 (Patch 5)：合法业务事件 edge_occurred_at 必须提供（NOT NULL），因此排序无需 NULLS LAST
-- 明确：failNextReplay 仅为测试 transient 标记，绝对不建列
CREATE TABLE safety.edge_pending_event (
    id                          UUID         NOT NULL,        -- 应用层 UUID
    event_id                    VARCHAR(64)  NOT NULL,        -- 边缘本地事件唯一编号
    edge_node_code              VARCHAR(32)  NOT NULL,        -- 来源边缘节点
    event_type                  VARCHAR(48)  NOT NULL,        -- person-intrusion / collision-risk 等
    business_key                VARCHAR(128),                 -- 业务键（组合追溯）
    edge_occurred_at            TIMESTAMPTZ  NOT NULL,        -- 边缘端发生时钟（补传建单取该时间，合法事件必有）
    received_at                 TIMESTAMPTZ  NOT NULL,        -- 边缘本地入缓存时间
    server_received_at          TIMESTAMPTZ,                  -- 中心服务器接收时间
    synced_at                   TIMESTAMPTZ,                  -- 补传成功时间
    idempotency_key             VARCHAR(128) NOT NULL,        -- 跨事件全局幂等键
    payload_hash                VARCHAR(64),                  -- 请求载荷 SHA-256 哈希，用于 409 冲突判定
    clock_offset_at_occurrence  BIGINT,                       -- 发生时刻边缘时钟偏差快照 (ms)
    rule_version_used           VARCHAR(24),                  -- 边缘判定使用的冻结规则版本
    risk_level_code             VARCHAR(16)  NOT NULL,        -- 风险等级
    payload_summary             JSONB        NOT NULL,        -- 必要业务摘要（不存视频/大图）
    local_linkage               JSONB,                        -- 边缘本地自治联动执行结果
    status_code                 VARCHAR(24)  NOT NULL DEFAULT 'PENDING', -- PENDING/SYNCING/SYNCED/FAILED/DUPLICATE
    retry_count                 INT          NOT NULL DEFAULT 0,
    last_retry_at               TIMESTAMPTZ,
    last_error                  TEXT,
    linked_alert_id             UUID,                         -- 补传成功后写入的 safety_alert.id
    sync_delay_sec              BIGINT,                       -- synced_at - edge_occurred_at
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_edge_pending_event PRIMARY KEY (id),
    CONSTRAINT uq_edge_pending_event_node_id UNIQUE (edge_node_code, event_id),
    CONSTRAINT uq_edge_pending_event_idempotency UNIQUE (idempotency_key)
);
COMMENT ON TABLE safety.edge_pending_event IS '边缘断网离线缓存事件补传队列（判重权威与原子重放源）';

-- 9.2 运维事件日志表 ops_event_log ---------------------------------------------
-- append-only，无 update/delete 路径
CREATE TABLE safety.ops_event_log (
    id             BIGINT       NOT NULL DEFAULT nextval('safety.seq_ops_event_log'), -- VERIFY ON OPENGAUSS
    edge_node_code VARCHAR(32),                               -- 关联节点（可空，支持中心运维事件）
    log_level      VARCHAR(16)  NOT NULL,                     -- INFO / WARNING / ERROR
    event_type     VARCHAR(48)  NOT NULL,                     -- NODE_OFFLINE, REPLAY_SUCCESS 等
    message        TEXT         NOT NULL,
    trace_id       VARCHAR(64),                               -- 链路追溯 ID
    occurred_at    TIMESTAMPTZ  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ops_event_log PRIMARY KEY (id)
);
COMMENT ON TABLE safety.ops_event_log IS '云边协同与系统运维日志流水（Append-only，不可删除）';

-- =============================================================================
-- 10. 平台发号辅助表（Platform Auxiliary Table，1 张表）
--     用于支撑业务编号（如 ALM-yyyyMMdd-NNN）在多实例并发下的排他行锁控制
--     明确：物理主键由上述 21 个 Sequence/UUID 支撑，业务编号发号器与之独立正交
-- =============================================================================
CREATE TABLE safety.sys_business_number (
    number_type    VARCHAR(32)  NOT NULL,                     -- ALERT / AI_EVENT / RULE / FENCE
    business_date  VARCHAR(16)  NOT NULL,                     -- yyyyMMdd 或 'GLOBAL'
    current_value  BIGINT       NOT NULL DEFAULT 0,
    lock_version   INT          NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_sys_business_number PRIMARY KEY (number_type, business_date)
);
COMMENT ON TABLE safety.sys_business_number IS '按日循环业务编号生成器计数表（多实例排他行锁发号器）';

-- =============================================================================
-- 11. 核心索引设计（基于真实 Query 契约）
-- =============================================================================

-- 11.1 告警检索与分页索引（基于 AlertQuery 真实入参组合）
-- 支持：状态 + 风险 + 区域 + 时间倒序分页
CREATE INDEX idx_safety_alert_list_query ON safety.safety_alert (
    status_code, risk_level_code, area_code, occurred_at DESC
);
-- 支持：责任人个人待办与处理中告警快速拉取
CREATE INDEX idx_safety_alert_assignee ON safety.safety_alert (
    assignee_user_code, status_code, occurred_at DESC
);
-- 支持：去重键开放告警检索（B5-02 核心权威索引，仅未关闭告警参与去重）
-- VERIFY ON OPENGAUSS: 部分索引（Partial Index）语法兼容性
CREATE INDEX idx_safety_alert_dedup_open ON safety.safety_alert (dedup_key, status_code)
    WHERE status_code <> 'CLOSED' AND status_code <> 'CANCELLED';
-- 回退方案（若 openGauss 不支持部分索引，采用普通复合索引 + 应用层事务过滤）:
-- CREATE INDEX idx_safety_alert_dedup_fallback ON safety.safety_alert (dedup_key, status_code);

-- 11.2 AI 识别事件检索索引（基于 AiEventQuery）
CREATE INDEX idx_ai_event_list_query ON safety.ai_event (
    status_code, risk_code, area_code, occurred_at DESC
);
CREATE INDEX idx_ai_event_camera ON safety.ai_event (
    camera_code, occurred_at DESC
);
CREATE INDEX idx_ai_event_linked_alert ON safety.ai_event (linked_alert_id);

-- 11.3 边缘离线事件稳定重放排序索引（基于 EdgeEventQueueRepository.findPendingForReplay）
-- 要求严格稳定排序：edge_occurred_at (ASC) + event_id (ASC 为 deterministic tie-break)
CREATE INDEX idx_edge_pending_replay_order ON safety.edge_pending_event (
    edge_node_code, status_code, edge_occurred_at ASC, event_id ASC
);

-- 11.4 规则与围栏检索索引
CREATE INDEX idx_safety_rule_category_status ON safety.safety_rule (category, status_code);
CREATE INDEX idx_safety_fence_status ON safety.safety_fence (status_code);

-- 11.5 运维日志时间线检索
CREATE INDEX idx_ops_event_log_node_time ON safety.ops_event_log (edge_node_code, occurred_at DESC);
CREATE INDEX idx_ops_event_log_type_time ON safety.ops_event_log (event_type, occurred_at DESC);

-- =============================================================================
-- 12. 延期实施表定义（DEFERRED TABLES -- 仅以注释保留设计）
--     本批活动 DDL 明确不执行建表，留待后续依赖就绪后再行落地
-- =============================================================================

/*
-- 12.1 动态字典表 sys_dictionary / sys_dictionary_item [DEFERRED]
-- 延期理由：当前所有状态 Code 已在 Java 模型与枚举类中固化，前端有一致的字典 Mapper，
-- 且一期无任何运维端动态增删字典的业务写路径。建空壳表只会增加维护开销。

-- 12.2 HTTP 请求幂等记录表 sys_idempotency_record [DEFERRED]
-- 延期理由：当前所有接口幂等由 Kvrocks (Redis 协议) 原生 TTL (600s) 提供毫秒级拦截；
-- 短期内无超过 10 分钟跨度的持久化幂等审计需求。

-- 12.3 统一文件附件台账表 safety_attachment [DEFERRED]
-- 延期理由：当前系统尚未接入正式对象存储服务（MinIO/S3），现场照片与截图仍为模拟路径，
-- 待文件存储技术栈明确后再行标准化建表。

-- 12.4 事务发布发件箱 outbox_event [P1 / DEFERRED UNTIL MQ]
-- 延期理由：单实例 JDBC 演进阶段，直接由 Spring TransactionSynchronization.afterCommit 钩子
-- 直发 WebSocket 即可达成事务后发布的一致性；仅在引入 RocketMQ 跨实例广播时才需要本表。

-- 12.5 高频时序数据表 safety_personnel_track / collision_measurement / edge_node_metric [PROHIBITED IN OPENGAUSS]
-- 架构边界红线：人员高频连续轨迹（每秒数点）、防碰撞毫米波高频测距（10-50Hz）、边缘 CPU/内存每秒监控点，
-- 属于典型时序写入负载，严禁写入 openGauss 关系型数据库，正式环境统一由 openGemini 时序数据库承载！
*/
