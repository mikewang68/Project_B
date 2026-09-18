-- ============================================================================
-- SYS schema：数据字典分类/字典项、操作日志、系统配置
-- openGauss 6.0.0/6.0.5（PG 兼容），逻辑删除 deleted，唯一约束采用部分唯一索引。
-- 执行：gsql -d project_b -U gaussdb -f 01-sys-schema.sql
-- ============================================================================
SET search_path TO sys;

-- ---------- 字典分类 ----------
CREATE TABLE IF NOT EXISTS sys_dict_type (
    id          VARCHAR(32)  PRIMARY KEY,
    dict_code   VARCHAR(64)  NOT NULL,
    dict_name   VARCHAR(128) NOT NULL,
    remark      VARCHAR(255),
    status      VARCHAR(16)  NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE sys_dict_type IS '数据字典分类';
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_dict_code ON sys_dict_type(dict_code) WHERE deleted = 0;

-- ---------- 字典项 ----------
CREATE TABLE IF NOT EXISTS sys_dict_item (
    id          VARCHAR(64)  PRIMARY KEY,
    type_code   VARCHAR(64)  NOT NULL,
    item_value  VARCHAR(128) NOT NULL,
    item_label  VARCHAR(128) NOT NULL,
    tag_type    VARCHAR(16),
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    status      VARCHAR(16)  NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
    remark      VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE sys_dict_item IS '数据字典项，同一分类下 value 唯一';
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_dict_item_value
    ON sys_dict_item(type_code, item_value) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_sys_dict_item_type ON sys_dict_item(type_code);

-- ---------- 操作/登录日志（IAM、SYS 共用，表归 SYS 所有） ----------
CREATE TABLE IF NOT EXISTS sys_operation_log (
    id             VARCHAR(32)  PRIMARY KEY,
    kind           VARCHAR(16)  NOT NULL DEFAULT 'operation',
    username       VARCHAR(64),
    user_id        VARCHAR(32),
    module         VARCHAR(32)  NOT NULL,
    action         VARCHAR(32)  NOT NULL,
    target         VARCHAR(255),
    detail         VARCHAR(512),
    request_method VARCHAR(16),
    request_uri    VARCHAR(255),
    ip             VARCHAR(64),
    result         VARCHAR(16)  NOT NULL DEFAULT 'success',
    duration_ms    INTEGER,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE sys_operation_log IS '统一操作/登录日志（IAM 与 SYS 均写入）';
CREATE INDEX IF NOT EXISTS idx_sys_log_time ON sys_operation_log(created_at);
CREATE INDEX IF NOT EXISTS idx_sys_log_module ON sys_operation_log(module);
CREATE INDEX IF NOT EXISTS idx_sys_log_result ON sys_operation_log(result);
CREATE INDEX IF NOT EXISTS idx_sys_log_user ON sys_operation_log(username);

-- ---------- 系统配置 ----------
CREATE TABLE IF NOT EXISTS sys_config (
    id           VARCHAR(32)  PRIMARY KEY,
    config_key   VARCHAR(128) NOT NULL,
    config_name  VARCHAR(128) NOT NULL,
    config_value TEXT,
    config_type  VARCHAR(16)  NOT NULL,
    config_group VARCHAR(64)  NOT NULL,
    options_json TEXT,
    unit         VARCHAR(16),
    remark       VARCHAR(255),
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    editable     SMALLINT     NOT NULL DEFAULT 1,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE sys_config IS '系统配置（文本/数值/开关/下拉，值统一以文本存储）';
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_config_key ON sys_config(config_key);
CREATE INDEX IF NOT EXISTS idx_sys_config_group ON sys_config(config_group);

-- ============================================================================
-- 跨服务授权：IAM 后端仅向统一日志表“写入”登录/用户/角色操作日志（最小权限，
-- IAM 端 OperationLogger 只做 INSERT，不授予 SELECT，避免越权读取全量操作日志）
-- ============================================================================
GRANT USAGE ON SCHEMA sys TO iam_app;
GRANT INSERT ON sys_operation_log TO iam_app;

-- SYS application account: full DML on its own schema
GRANT USAGE ON SCHEMA sys TO sys_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA sys TO sys_app;
