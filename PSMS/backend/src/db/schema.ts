/**
 * PSMS 业务库（openGauss）表结构定义。
 *
 * 设计原则
 *  1. **保持契约 ID 语义**：主键直接用契约里的语义化字符串（PLAN-001 / WO-001 …），
 *     与前端 DO-xxx 契约、审计日志的 objectId 完全一致，便于人工排查与跨表对照。
 *  2. **内嵌结构拆子表**：原文档模型里的内嵌数组（货物明细、证据、附件、输入信号、
 *     变更历史、依赖、班组）一律落成子表，不用 jsonb —— 便于独立查询、聚合与约束。
 *  3. **真无结构的字段才用 jsonb**：`supplements` / `specs` / `metadata` / `payload` /
 *     审计 before、after 这些"结构随终端或外部系统演进"的载荷才用 jsonb。
 *  4. **枚举用 TEXT + CHECK**：不使用数据库 enum 类型，避免后续增删取值需要类型迁移。
 *  5. **外键只用在"内嵌子表 → 主表"**：跨实体引用（工单→计划、任务→工单）故意不加外键，
 *     避免删一张主表时级联清掉需要留档的历史单据；这类引用由应用层保证并建立索引。
 *  6. **幂等**：全部 DDL 都是 IF NOT EXISTS，可重复执行。
 *
 * 存储分工（组内基线）：本文件是「事务/业务」侧；设备遥测走 openGemini，
 * measurement 与字段定义见 db/openGeminiClient.ts。
 */

/** 业务表（不含子表）—— 供统计与文档生成使用 */
export const CORE_TABLES = [
  'users',
  'plans',
  'work_orders',
  'tasks',
  'equipment',
  'exceptions',
  'interlocks',
  'appointments',
  'offline_packets',
  'config_versions',
  'audit_logs',
] as const;

/** 内嵌结构拆出的子表 */
export const CHILD_TABLES = [
  'user_data_scopes',
  'plan_cargo_items',
  'work_order_crew',
  'task_crew',
  'task_dependencies',
  'exception_evidence',
  'interlock_input_signals',
  'appointment_documents',
  'offline_packet_conflict_fields',
  'config_change_history',
] as const;

export const ALL_TABLES = [...CORE_TABLES, ...CHILD_TABLES] as const;

/** 审计日志保留期兜底配置键 */
export const AUDIT_RETENTION_SETTING = 'psms.audit_retention_days';

const DDL: string[] = [
  // ==========================================================================
  // 1. users —— 账号（对应 DO-014 的账号侧视图）
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS users (
    id            TEXT PRIMARY KEY,
    actor_id      TEXT NOT NULL UNIQUE,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    display_name  TEXT NOT NULL,
    role_code     TEXT NOT NULL
                  CHECK (role_code IN ('super_admin','admin','scheduler','dispatcher','operator','viewer')),
    online        BOOLEAN NOT NULL DEFAULT FALSE,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
  )`,
  `CREATE INDEX IF NOT EXISTS idx_users_role ON users (role_code)`,
  `CREATE INDEX IF NOT EXISTS idx_users_online ON users (online) WHERE online = TRUE`,

  // ==========================================================================
  // 2. user_data_scopes —— 数据域（原 dataScope: string[]）
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS user_data_scopes (
    actor_id   TEXT NOT NULL REFERENCES users (actor_id) ON DELETE CASCADE,
    scope      TEXT NOT NULL,
    PRIMARY KEY (actor_id, scope)
  )`,

  // ==========================================================================
  // 3. plans —— 外部到发计划（DO-001 调度侧视图）
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS plans (
    id                  TEXT PRIMARY KEY,
    plan_batch_no       TEXT NOT NULL UNIQUE,
    train_no            TEXT NOT NULL,
    cargo_type          TEXT NOT NULL,
    cargo_description   TEXT NOT NULL DEFAULT '',
    estimated_weight    NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (estimated_weight >= 0),
    weight_unit         TEXT NOT NULL DEFAULT 'ton',
    source_station      TEXT NOT NULL,
    destination_station TEXT NOT NULL DEFAULT '',
    arrive_time         TIMESTAMPTZ NOT NULL,
    track_no            TEXT NOT NULL DEFAULT '',
    work_area           TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'PENDING_CONFIRM'
                        CHECK (status IN ('PENDING_CONFIRM','CONFIRMED','IN_PROGRESS','COMPLETED','CANCELLED')),
    priority            TEXT NOT NULL DEFAULT 'MEDIUM'
                        CHECK (priority IN ('HIGH','MEDIUM','LOW')),
    confirmed_by        TEXT,
    confirmed_at        TIMESTAMPTZ,
    supplier_name       TEXT,
    supplier_contact    TEXT,
    supplier_phone      TEXT,
    supplements         JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
  )`,
  `CREATE INDEX IF NOT EXISTS idx_plans_work_area_status ON plans (work_area, status)`,
  `CREATE INDEX IF NOT EXISTS idx_plans_arrive_time ON plans (arrive_time DESC)`,
  `CREATE INDEX IF NOT EXISTS idx_plans_status_updated ON plans (status, updated_at DESC)`,
  `CREATE INDEX IF NOT EXISTS idx_plans_train_no ON plans (train_no)`,
  `CREATE INDEX IF NOT EXISTS idx_plans_cargo_type ON plans (cargo_type)`,

  // ==========================================================================
  // 4. plan_cargo_items —— 计划货物明细（原 cargoItems 内嵌）
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS plan_cargo_items (
    plan_id  TEXT NOT NULL REFERENCES plans (id) ON DELETE CASCADE,
    item_no  INTEGER NOT NULL CHECK (item_no > 0),
    name     TEXT NOT NULL,
    quantity NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    unit     TEXT NOT NULL DEFAULT '',
    weight   NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (weight >= 0),
    remarks  TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (plan_id, item_no)
  )`,
  `CREATE INDEX IF NOT EXISTS idx_plan_cargo_items_name ON plan_cargo_items (name)`,

  // ==========================================================================
  // 5. work_orders —— 作业工单（DO-005）
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS work_orders (
    id                 TEXT PRIMARY KEY,
    plan_id            TEXT NOT NULL,
    plan_batch_no      TEXT NOT NULL,
    task_id            TEXT,
    work_area          TEXT NOT NULL,
    equipment_id       TEXT,
    equipment_name     TEXT NOT NULL DEFAULT '',
    assigned_operator  TEXT,
    status             TEXT NOT NULL DEFAULT 'DRAFT'
                       CHECK (status IN ('DRAFT','READY','ASSIGNED','ACCEPTED','IN_PROGRESS','PAUSED','COMPLETED','CANCELLED')),
    order_type         TEXT NOT NULL DEFAULT 'OTHER'
                       CHECK (order_type IN ('LOADING','UNLOADING','TRANSFER','MAINTENANCE','OTHER')),
    priority           TEXT NOT NULL DEFAULT 'MEDIUM'
                       CHECK (priority IN ('HIGH','MEDIUM','LOW')),
    description        TEXT NOT NULL DEFAULT '',
    instructions       TEXT,
    estimated_duration INTEGER CHECK (estimated_duration IS NULL OR estimated_duration >= 0),
    actual_start_time  TIMESTAMPTZ,
    actual_end_time    TIMESTAMPTZ,
    accepted_by        TEXT,
    accepted_at        TIMESTAMPTZ,
    pause_reason       TEXT,
    cancel_reason      TEXT,
    feedback_quality   TEXT CHECK (feedback_quality IS NULL OR feedback_quality IN ('GOOD','FAIR','POOR')),
    feedback_comment   TEXT,
    feedback_by        TEXT,
    feedback_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_wo_time_order CHECK (
      actual_end_time IS NULL OR actual_start_time IS NULL OR actual_end_time >= actual_start_time
    )
  )`,
  `CREATE INDEX IF NOT EXISTS idx_work_orders_work_area_status ON work_orders (work_area, status)`,
  `CREATE INDEX IF NOT EXISTS idx_work_orders_equipment_status ON work_orders (equipment_id, status)`,
  `CREATE INDEX IF NOT EXISTS idx_work_orders_plan_status ON work_orders (plan_id, status)`,
  `CREATE INDEX IF NOT EXISTS idx_work_orders_created ON work_orders (created_at DESC)`,

  // ==========================================================================
  // 6. work_order_crew —— 工单班组（原 assignedCrew: string[]）
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS work_order_crew (
    work_order_id TEXT NOT NULL REFERENCES work_orders (id) ON DELETE CASCADE,
    crew          TEXT NOT NULL,
    PRIMARY KEY (work_order_id, crew)
  )`,

  // ==========================================================================
  // 7. tasks —— 任务拆解产物
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS tasks (
    id                 TEXT PRIMARY KEY,
    plan_id            TEXT NOT NULL,
    work_order_id      TEXT,
    plan_batch_no      TEXT NOT NULL,
    task_no            TEXT NOT NULL,
    name               TEXT NOT NULL,
    description        TEXT NOT NULL DEFAULT '',
    work_area          TEXT NOT NULL,
    equipment_id       TEXT,
    status             TEXT NOT NULL DEFAULT 'PENDING'
                       CHECK (status IN ('PENDING','READY','IN_PROGRESS','PAUSED','COMPLETED','FAILED','CANCELLED')),
    order_no           INTEGER NOT NULL DEFAULT 1,
    parent_task_id     TEXT,
    estimated_duration INTEGER CHECK (estimated_duration IS NULL OR estimated_duration >= 0),
    actual_duration    INTEGER CHECK (actual_duration IS NULL OR actual_duration >= 0),
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_tasks_plan_task_no UNIQUE (plan_id, task_no)
  )`,
  `CREATE INDEX IF NOT EXISTS idx_tasks_plan_order ON tasks (plan_id, order_no)`,
  `CREATE INDEX IF NOT EXISTS idx_tasks_plan_status ON tasks (plan_id, status)`,
  `CREATE INDEX IF NOT EXISTS idx_tasks_work_order ON tasks (work_order_id)`,
  `CREATE INDEX IF NOT EXISTS idx_tasks_parent ON tasks (parent_task_id)`,

  // ==========================================================================
  // 8. task_crew —— 任务班组（原 assignedCrew）
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS task_crew (
    task_id TEXT NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    crew    TEXT NOT NULL,
    PRIMARY KEY (task_id, crew)
  )`,

  // ==========================================================================
  // 9. task_dependencies —— 任务前置依赖（原 dependsOn: string[]）
  //    依赖是"任务→任务"的图，必须能被独立查询（找环、拓扑排序）
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS task_dependencies (
    task_id             TEXT NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    depends_on_task_id  TEXT NOT NULL,
    PRIMARY KEY (task_id, depends_on_task_id),
    CONSTRAINT chk_task_dep_not_self CHECK (task_id <> depends_on_task_id)
  )`,
  `CREATE INDEX IF NOT EXISTS idx_task_dependencies_reverse ON task_dependencies (depends_on_task_id)`,

  // ==========================================================================
  // 10. equipment —— 设备台账
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS equipment (
    id                TEXT PRIMARY KEY,
    equipment_id      TEXT NOT NULL UNIQUE,
    name              TEXT NOT NULL,
    type              TEXT NOT NULL,
    model             TEXT NOT NULL DEFAULT '',
    specs             JSONB,
    work_area         TEXT NOT NULL,
    status            TEXT NOT NULL DEFAULT 'OFFLINE'
                      CHECK (status IN ('ONLINE','OFFLINE','MAINTENANCE','FAULT')),
    last_heartbeat    TIMESTAMPTZ,
    last_telemetry_at TIMESTAMPTZ,
    network_zone      TEXT,
    protocol          TEXT,
    ip_address        TEXT,
    port              INTEGER CHECK (port IS NULL OR (port > 0 AND port <= 65535)),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
  )`,
  `CREATE INDEX IF NOT EXISTS idx_equipment_work_area_status ON equipment (work_area, status)`,
  `CREATE INDEX IF NOT EXISTS idx_equipment_type_status ON equipment (type, status)`,
  `CREATE INDEX IF NOT EXISTS idx_equipment_online ON equipment (last_heartbeat DESC)`,

  // ==========================================================================
  // 11. exceptions —— 生产异常（DO-009 后端视图）
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS exceptions (
    id              TEXT PRIMARY KEY,
    exception_id    TEXT NOT NULL UNIQUE,
    type            TEXT NOT NULL CHECK (type IN ('FLOW','SAFETY','EQUIPMENT','INTERFACE','DATA')),
    severity        TEXT NOT NULL DEFAULT 'MAJOR' CHECK (severity IN ('CRITICAL','MAJOR','MINOR','INFO')),
    source_id       TEXT NOT NULL,
    source_type     TEXT NOT NULL CHECK (source_type IN ('plan','workOrder','task','equipment','interlock')),
    title           TEXT NOT NULL,
    description     TEXT NOT NULL DEFAULT '',
    equipment_id    TEXT,
    work_area       TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'OPEN'
                    CHECK (status IN ('OPEN','ACKNOWLEDGED','IN_PROGRESS','RESOLVED','CLOSED','DISMISSED')),
    assigned_to     TEXT,
    acknowledged_by TEXT,
    acknowledged_at TIMESTAMPTZ,
    resolved_by     TEXT,
    resolved_at     TIMESTAMPTZ,
    closed_by       TEXT,
    closed_at       TIMESTAMPTZ,
    resolution      TEXT,
    root_cause      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
  )`,
  `CREATE INDEX IF NOT EXISTS idx_exceptions_work_area_status ON exceptions (work_area, status)`,
  `CREATE INDEX IF NOT EXISTS idx_exceptions_type_severity ON exceptions (type, severity)`,
  `CREATE INDEX IF NOT EXISTS idx_exceptions_status_created ON exceptions (status, created_at DESC)`,
  `CREATE INDEX IF NOT EXISTS idx_exceptions_source ON exceptions (source_type, source_id)`,

  // ==========================================================================
  // 12. exception_evidence —— 异常证据（原 evidence 内嵌）
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS exception_evidence (
    exception_id TEXT NOT NULL REFERENCES exceptions (id) ON DELETE CASCADE,
    seq          INTEGER NOT NULL CHECK (seq > 0),
    type         TEXT NOT NULL DEFAULT '',
    url          TEXT NOT NULL DEFAULT '',
    description  TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (exception_id, seq)
  )`,

  // ==========================================================================
  // 13. interlocks —— 安全联锁
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS interlocks (
    id                   TEXT PRIMARY KEY,
    interlock_id         TEXT NOT NULL UNIQUE,
    name                 TEXT NOT NULL,
    type                 TEXT NOT NULL CHECK (type IN ('HARD','SOFT','PROCEDURAL')),
    category             TEXT NOT NULL CHECK (category IN ('ACCESS','EQUIPMENT','AREA','ENVIRONMENT')),
    source_id            TEXT NOT NULL,
    equipment_id         TEXT,
    work_area            TEXT NOT NULL,
    rule                 TEXT NOT NULL,
    description          TEXT NOT NULL DEFAULT '',
    status               TEXT NOT NULL DEFAULT 'ARMED'
                         CHECK (status IN ('ARMED','TRIGGERED','OVERRIDDEN','RESET','DISABLED')),
    triggered_at         TIMESTAMPTZ,
    triggered_by         TEXT,
    trigger_reason       TEXT,
    override_requested_by TEXT,
    override_approved_by TEXT,
    override_reason      TEXT,
    override_expires_at  TIMESTAMPTZ,
    reset_by             TEXT,
    reset_at             TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_interlock_override CHECK (
      override_approved_by IS NULL OR override_requested_by IS NOT NULL
    )
  )`,
  `CREATE INDEX IF NOT EXISTS idx_interlocks_work_area_status ON interlocks (work_area, status)`,
  `CREATE INDEX IF NOT EXISTS idx_interlocks_status_triggered ON interlocks (status, triggered_at DESC)`,
  `CREATE INDEX IF NOT EXISTS idx_interlocks_override_expires ON interlocks (override_expires_at) WHERE override_expires_at IS NOT NULL`,

  // ==========================================================================
  // 14. interlock_input_signals —— 联锁输入信号（原 inputSignals 内嵌）
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS interlock_input_signals (
    interlock_id   TEXT NOT NULL REFERENCES interlocks (id) ON DELETE CASCADE,
    seq            INTEGER NOT NULL CHECK (seq > 0),
    equipment_id   TEXT,
    point_code     TEXT,
    expected_value TEXT,
    actual_value   TEXT,
    PRIMARY KEY (interlock_id, seq)
  )`,

  // ==========================================================================
  // 15. appointments —— 公路预约与叫号
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS appointments (
    id                 TEXT PRIMARY KEY,
    appointment_id     TEXT NOT NULL UNIQUE,
    vehicle_plate      TEXT NOT NULL,
    vehicle_type       TEXT NOT NULL,
    driver_name        TEXT NOT NULL,
    driver_phone       TEXT NOT NULL,
    driver_id_card     TEXT NOT NULL,
    company            TEXT NOT NULL DEFAULT '',
    cargo_type         TEXT NOT NULL,
    estimated_weight   NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (estimated_weight >= 0),
    planned_arrive_time TIMESTAMPTZ NOT NULL,
    actual_arrive_time TIMESTAMPTZ,
    check_in_time      TIMESTAMPTZ,
    called_at          TIMESTAMPTZ,
    enter_time         TIMESTAMPTZ,
    exit_time          TIMESTAMPTZ,
    queue_number       INTEGER CHECK (queue_number IS NULL OR queue_number > 0),
    status             TEXT NOT NULL DEFAULT 'PENDING'
                       CHECK (status IN ('PENDING','APPROVED','CHECKED_IN','QUEUED','CALLED','ON_SITE','COMPLETED','CANCELLED','NO_SHOW')),
    gate_no            TEXT,
    parking_bay        TEXT,
    route              TEXT,
    remarks            TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_appointment_exit_after_enter CHECK (
      exit_time IS NULL OR enter_time IS NULL OR exit_time >= enter_time
    )
  )`,
  `CREATE INDEX IF NOT EXISTS idx_appointments_planned_arrive ON appointments (planned_arrive_time)`,
  `CREATE INDEX IF NOT EXISTS idx_appointments_status_queue ON appointments (status, queue_number)`,
  `CREATE INDEX IF NOT EXISTS idx_appointments_vehicle_plate ON appointments (vehicle_plate)`,
  `CREATE INDEX IF NOT EXISTS idx_appointments_driver_phone ON appointments (driver_phone)`,
  // 同一排队序号在"未结束"的预约里必须唯一，避免叫号重号
  `CREATE UNIQUE INDEX IF NOT EXISTS uk_appointments_active_queue
     ON appointments (queue_number)
     WHERE queue_number IS NOT NULL
       AND status IN ('QUEUED','CALLED','ON_SITE')`,

  // ==========================================================================
  // 16. appointment_documents —— 预约资质附件（原 documents 内嵌）
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS appointment_documents (
    appointment_id TEXT NOT NULL REFERENCES appointments (id) ON DELETE CASCADE,
    seq            INTEGER NOT NULL CHECK (seq > 0),
    type           TEXT NOT NULL DEFAULT '',
    url            TEXT NOT NULL DEFAULT '',
    verified       BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (appointment_id, seq)
  )`,

  // ==========================================================================
  // 17. offline_packets —— PDA 离线数据包
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS offline_packets (
    id              TEXT PRIMARY KEY,
    packet_id       TEXT NOT NULL UNIQUE,
    terminal_id     TEXT NOT NULL,
    operator_id     TEXT NOT NULL,
    work_area       TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT','SYNCED','CONFLICT','RESOLVED','FAILED')),
    version         INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
    server_version  INTEGER CHECK (server_version IS NULL OR server_version >= 1),
    payload         JSONB NOT NULL,
    sync_attempts   INTEGER NOT NULL DEFAULT 0 CHECK (sync_attempts >= 0),
    last_sync_at    TIMESTAMPTZ,
    resolution      TEXT CHECK (resolution IS NULL OR resolution IN ('ACCEPT_LOCAL','ACCEPT_SERVER','MANUAL_MERGE','DISCARD')),
    resolved_by     TEXT,
    resolved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
  )`,
  `CREATE INDEX IF NOT EXISTS idx_offline_packets_terminal_status ON offline_packets (terminal_id, status)`,
  `CREATE INDEX IF NOT EXISTS idx_offline_packets_status_updated ON offline_packets (status, updated_at DESC)`,
  `CREATE INDEX IF NOT EXISTS idx_offline_packets_operator ON offline_packets (operator_id)`,

  // ==========================================================================
  // 18. offline_packet_conflict_fields —— 冲突字段（原 conflictFields: string[]）
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS offline_packet_conflict_fields (
    packet_id TEXT NOT NULL REFERENCES offline_packets (id) ON DELETE CASCADE,
    field     TEXT NOT NULL,
    PRIMARY KEY (packet_id, field)
  )`,

  // ==========================================================================
  // 19. config_versions —— 系统配置版本（DO-015）
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS config_versions (
    id                     TEXT PRIMARY KEY,
    config_id              TEXT NOT NULL UNIQUE,
    config_version         TEXT NOT NULL DEFAULT 'CFG-1.0',
    display_name           TEXT NOT NULL DEFAULT 'B项目生产调度管理系统',
    default_scenario_id    TEXT NOT NULL DEFAULT 'SCN-01',
    rule_version           TEXT NOT NULL DEFAULT 'RULE-1.0',
    dispatch_strategy      TEXT NOT NULL DEFAULT 'BALANCED'
                           CHECK (dispatch_strategy IN ('BALANCED','PRIORITY_FIRST','RESOURCE_FIRST','OPTIMAL')),
    recommendation_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    offline_sync_enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    report_period          TEXT NOT NULL DEFAULT 'DAILY'
                           CHECK (report_period IN ('SHIFT','DAILY','MONTHLY')),
    audit_retention_days   INTEGER NOT NULL DEFAULT 365
                           CHECK (audit_retention_days >= 1 AND audit_retention_days <= 3650),
    status                 TEXT NOT NULL DEFAULT 'DRAFT'
                           CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','PUBLISHED','ROLLED_BACK')),
    version                INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
    scenario_id            TEXT NOT NULL DEFAULT 'SCN-01',
    published_at           TIMESTAMPTZ,
    published_by           TEXT,
    created_by             TEXT NOT NULL DEFAULT 'system',
    updated_by             TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_config_published CHECK (
      (status = 'PUBLISHED' AND published_at IS NOT NULL)
      OR status <> 'PUBLISHED'
      OR published_at IS NULL
    )
  )`,
  `CREATE INDEX IF NOT EXISTS idx_config_versions_status ON config_versions (status)`,

  // ==========================================================================
  // 20. config_change_history —— 配置变更历史（原 changeHistory 内嵌）
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS config_change_history (
    config_id  TEXT NOT NULL REFERENCES config_versions (id) ON DELETE CASCADE,
    seq        INTEGER NOT NULL CHECK (seq > 0),
    field      TEXT NOT NULL DEFAULT '',
    old_value  JSONB,
    new_value  JSONB,
    changed_by TEXT,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (config_id, seq)
  )`,

  // ==========================================================================
  // 21. audit_logs —— 审计留痕（业务审计 + HTTP 访问留痕，统一超集模型）
  // ==========================================================================
  `CREATE TABLE IF NOT EXISTS audit_logs (
    id               TEXT PRIMARY KEY,
    actor_id         TEXT NOT NULL,
    actor_role       TEXT,
    operator_terminal TEXT NOT NULL DEFAULT 'WEB-BACKEND',
    action           TEXT NOT NULL,
    object_type      TEXT NOT NULL DEFAULT '',
    object_id        TEXT NOT NULL DEFAULT '',
    before_state     JSONB,
    after_state      JSONB,
    reason           TEXT NOT NULL DEFAULT '',
    trace_id         TEXT NOT NULL DEFAULT '',
    occurred_at      TIMESTAMPTZ,
    resource         TEXT,
    resource_id      TEXT,
    status_code      INTEGER CHECK (status_code IS NULL OR (status_code >= 100 AND status_code <= 599)),
    duration_ms      INTEGER CHECK (duration_ms IS NULL OR duration_ms >= 0),
    ip               TEXT,
    user_agent       TEXT,
    request_body     TEXT,
    response_summary TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
  )`,
  `CREATE INDEX IF NOT EXISTS idx_audit_logs_occurred ON audit_logs (occurred_at DESC)`,
  `CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_occurred ON audit_logs (actor_id, occurred_at DESC)`,
  `CREATE INDEX IF NOT EXISTS idx_audit_logs_object ON audit_logs (object_type, object_id, occurred_at DESC)`,
  `CREATE INDEX IF NOT EXISTS idx_audit_logs_action_occurred ON audit_logs (action, occurred_at DESC)`,
  `CREATE INDEX IF NOT EXISTS idx_audit_logs_trace ON audit_logs (trace_id)`,
  `CREATE INDEX IF NOT EXISTS idx_audit_logs_created ON audit_logs (created_at)`,
  // 审计日志只允许 INSERT/SELECT，不允许 UPDATE（由 GRANT 层面保证，见 docs 说明）
];

/** 表与列的中文注释（幂等，可重复执行） */
const COMMENTS: Array<[string, string, string | null]> = [
  ['users', '用户账号；id 与 actor_id 同值，均来自契约的 ACTOR-xxx', null],
  ['users', 'actor_id', '操作者标识，JWT payload 与审计日志均引用此字段'],
  ['users', 'role_code', '当前后端角色集；与前端 13 个 RoleCode 尚未对齐'],
  ['users', 'online', '是否在线（演示态）'],
  ['user_data_scopes', '账号数据域白名单（作业区级）', null],
  ['user_data_scopes', 'scope', '数据域取值（作业区）'],

  ['plans', '外部到发计划（DO-001 调度侧视图）', null],
  ['plans', 'plan_batch_no', '计划批次号，业务唯一键'],
  ['plans', 'arrive_time', '到发时间，台账默认按此倒序'],
  ['plans', 'status', 'PENDING_CONFIRM/CONFIRMED/IN_PROGRESS/COMPLETED/CANCELLED'],
  ['plans', 'priority', 'HIGH/MEDIUM/LOW'],
  ['plans', 'supplements', '补充信息（随外部系统演进，故用 jsonb）'],
  ['plan_cargo_items', '计划货物明细（原内嵌 cargoItems）', null],
  ['plan_cargo_items', 'item_no', '明细行号，同计划内唯一'],

  ['work_orders', '作业工单（DO-005）', null],
  ['work_orders', 'plan_id', '所属计划；故意不加外键，避免删除计划时级联清掉需留档的工单'],
  ['work_orders', 'task_id', '来源任务；同上不加外键'],
  ['work_orders', 'status', 'DRAFT/READY/ASSIGNED/ACCEPTED/IN_PROGRESS/PAUSED/COMPLETED/CANCELLED'],
  ['work_orders', 'order_type', 'LOADING/UNLOADING/TRANSFER/MAINTENANCE/OTHER'],
  ['work_orders', 'feedback_quality', '执行反馈质量 GOOD/FAIR/POOR（原 executionFeedback 内嵌）'],
  ['work_order_crew', '工单班组（原内嵌 assignedCrew）', null],
  ['work_order_crew', 'crew', '班组标识'],

  ['tasks', '任务拆解产物', null],
  ['tasks', 'order_no', '任务序号，同计划内决定执行顺序'],
  ['tasks', 'parent_task_id', '父任务，用于任务树'],
  ['task_crew', '任务班组', null],
  ['task_crew', 'crew', '班组标识'],
  ['task_dependencies', '任务前置依赖（原 dependsOn 数组）', null],
  ['task_dependencies', 'depends_on_task_id', '被依赖的任务；反查可发现环与下游影响'],

  ['equipment', '设备台账', null],
  ['equipment', 'equipment_id', '设备编码，业务唯一键'],
  ['equipment', 'specs', '规格参数（随设备型号演进，故用 jsonb）'],
  ['equipment', 'status', 'ONLINE/OFFLINE/MAINTENANCE/FAULT'],
  ['equipment', 'last_telemetry_at', '最近一次遥测到达时间；遥测明细在 openGemini'],

  ['exceptions', '生产异常（DO-009 后端视图）', null],
  ['exceptions', 'source_type', 'plan/workOrder/task/equipment/interlock（多态引用，不设外键）'],
  ['exceptions', 'severity', 'CRITICAL/MAJOR/MINOR/INFO'],
  ['exceptions', 'status', 'OPEN/ACKNOWLEDGED/IN_PROGRESS/RESOLVED/CLOSED/DISMISSED'],
  ['exception_evidence', '异常证据条目（原内嵌 evidence）', null],

  ['interlocks', '安全联锁', null],
  ['interlocks', 'type', 'HARD/SOFT/PROCEDURAL'],
  ['interlocks', 'category', 'ACCESS/EQUIPMENT/AREA/ENVIRONMENT'],
  ['interlocks', 'status', 'ARMED/TRIGGERED/OVERRIDDEN/RESET/DISABLED'],
  ['interlocks', 'override_expires_at', '临时覆盖到期时间；到期后由应用层判定为失效（不删记录）'],
  ['interlock_input_signals', '联锁输入信号（原内嵌 inputSignals）', null],

  ['appointments', '公路预约与叫号', null],
  ['appointments', 'queue_number', '排队序号；未结束状态下唯一，避免叫号重号'],
  ['appointments', 'status', 'PENDING/APPROVED/CHECKED_IN/QUEUED/CALLED/ON_SITE/COMPLETED/CANCELLED/NO_SHOW'],
  ['appointment_documents', '预约资质附件（原内嵌 documents）', null],

  ['offline_packets', 'PDA 离线数据包', null],
  ['offline_packets', 'payload', '离线业务载荷（结构随终端版本演进，故用 jsonb）'],
  ['offline_packets', 'version', '客户端数据版本，用于与服务端版本比对'],
  ['offline_packets', 'resolution', 'ACCEPT_LOCAL/ACCEPT_SERVER/MANUAL_MERGE/DISCARD'],
  ['offline_packet_conflict_fields', '冲突字段清单（原 conflictFields 数组）', null],
  ['offline_packet_conflict_fields', 'field', '发生冲突的字段名'],

  ['config_versions', '系统配置版本（DO-015）', null],
  ['config_versions', 'version', '业务乐观锁版本，每次写命令 +1'],
  ['config_versions', 'audit_retention_days', '审计保留天数 1~3650，驱动审计清理任务'],
  ['config_versions', 'status', 'DRAFT/SUBMITTED/APPROVED/PUBLISHED/ROLLED_BACK'],
  ['config_change_history', '配置变更历史（原内嵌 changeHistory）', null],

  ['audit_logs', '审计留痕；同时承载业务审计与 HTTP 访问留痕（字段为超集）', null],
  ['audit_logs', 'occurred_at', '业务事件发生时间；访问留痕该字段为空，按 created_at 排序'],
  ['audit_logs', 'before_state', '变更前快照（业务审计）'],
  ['audit_logs', 'after_state', '变更后快照（业务审计）'],
  ['audit_logs', 'request_body', '请求体摘要（访问留痕，已截断敏感字段）'],
  ['audit_logs', 'status_code', 'HTTP 状态码（访问留痕）'],
];

/**
 * 注释语句：COMMENT ON 支持重复执行。
 *
 * 元组格式为 [表名, X, Y]：
 *   Y 为 null  → X 是【表的注释文本】
 *   Y 非 null  → X 是【列名】，Y 是列注释文本
 * 下面顺带做一次列名校验 —— 曾经把中文说明误当列名写进 X，
 * 结果生成出 `COMMENT ON COLUMN t.中文 IS '...'` 这种非法 SQL 直到运行时才炸。
 */
const IDENTIFIER = /^[a-z_][a-z0-9_]*$/;

const COMMENT_DDL: string[] = COMMENTS.map(([table, second, third]) => {
  const escape = (text: string) => text.replace(/'/g, "''");
  if (third === null) {
    return `COMMENT ON TABLE ${table} IS '${escape(second)}'`;
  }
  if (!IDENTIFIER.test(second)) {
    throw new Error(
      `schema.ts 注释元组写错：${table} 的列名 "${second}" 不是合法标识符（说明文字应放在第三个位置）`,
    );
  }
  return `COMMENT ON COLUMN ${table}.${second} IS '${escape(third)}'`;
});


export const SCHEMA_STATEMENTS: string[] = [...DDL, ...COMMENT_DDL];

/** 清理顺序（先子后父），供演示重置与种子脚本使用 */
export const TRUNCATE_ORDER: string[] = [
  'config_change_history',
  'appointment_documents',
  'interlock_input_signals',
  'exception_evidence',
  'offline_packet_conflict_fields',
  'task_dependencies',
  'task_crew',
  'work_order_crew',
  'plan_cargo_items',
  'user_data_scopes',
  'audit_logs',
  'tasks',
  'work_orders',
  'appointments',
  'offline_packets',
  'interlocks',
  'exceptions',
  'equipment',
  'config_versions',
  'plans',
  'users',
];

/**
 * 一致性守卫：清理清单必须恰好覆盖全部表。
 *
 * 真实教训：曾漏掉 `offline_packets`（父表）——演示重置清空其余 20 张表后，
 * 种子重插时 `OFF-PKG-001` 主键冲突，种子在半途失败，
 * 导致 `config_versions` 没灌回去、`/api/settings` 返回 404。
 * 该清单与 ALL_TABLES 作为集合必须相等，否则在模块加载时就直接报错，
 * 让这类问题不可能被静默提交。
 */
{
  const all = new Set<string>(ALL_TABLES);
  const ordered = new Set<string>(TRUNCATE_ORDER);
  const missing = [...all].filter((t) => !ordered.has(t));
  const extra = [...ordered].filter((t) => !all.has(t));
  if (missing.length > 0 || extra.length > 0) {
    throw new Error(
      `schema.ts 的 TRUNCATE_ORDER 与表清单不一致：` +
      `缺少 [${missing.join(', ') || '无'}]，多余 [${extra.join(', ') || '无'}]`,
    );
  }
}
