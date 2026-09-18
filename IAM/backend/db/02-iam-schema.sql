-- ============================================================================
-- IAM schema：用户 / 角色 / 权限目录 / 用户角色 / 角色权限 / 组织树
-- openGauss 6.0.5（PG 兼容），逻辑删除 deleted，唯一约束采用部分唯一索引。
-- 执行：gsql -d project_b -U gaussdb -f 02-iam-schema.sql
-- ============================================================================
SET search_path TO iam;

-- ---------- 用户 ----------
CREATE TABLE IF NOT EXISTS iam_user (
    id                 VARCHAR(32)  PRIMARY KEY,
    username           VARCHAR(64)  NOT NULL,
    password_hash      VARCHAR(100) NOT NULL,
    display_name       VARCHAR(64)  NOT NULL,
    phone              VARCHAR(32),
    email              VARCHAR(128),
    status             VARCHAR(16)  NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
    zone               VARCHAR(64),
    company            VARCHAR(64),
    dept               VARCHAR(64),
    group_name         VARCHAR(64),
    zone_code          VARCHAR(32),
    company_code       VARCHAR(32),
    dept_code          VARCHAR(32),
    group_code         VARCHAR(32),
    org_codes          VARCHAR(255),
    org_path           VARCHAR(255),
    blockchain_id      VARCHAR(128),
    blockchain_address VARCHAR(128),
    last_login_at      TIMESTAMP,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted            SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE iam_user IS 'IAM 用户表（BCrypt 密码，逻辑删除）';
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_user_username ON iam_user(username) WHERE deleted = 0;

-- ---------- 角色 ----------
CREATE TABLE IF NOT EXISTS iam_role (
    id          VARCHAR(32)  PRIMARY KEY,
    role_code   VARCHAR(64)  NOT NULL,
    role_name   VARCHAR(64)  NOT NULL,
    description VARCHAR(255),
    status      VARCHAR(16)  NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE iam_role IS 'IAM 角色表';
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_role_code ON iam_role(role_code) WHERE deleted = 0;

-- ---------- 权限目录（系统/菜单/按钮三级节点） ----------
CREATE TABLE IF NOT EXISTS iam_permission (
    id              VARCHAR(128) PRIMARY KEY,
    parent_id       VARCHAR(128),
    node_type       VARCHAR(16)  NOT NULL,
    permission_code VARCHAR(128),
    permission_name VARCHAR(128) NOT NULL,
    system_code     VARCHAR(32)  NOT NULL,
    route           VARCHAR(255),
    icon            VARCHAR(64),
    sort_order      INTEGER      NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_permission IS '权限目录树：SYSTEM/MENU 节点 + BUTTON 权限编码节点';
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_perm_code ON iam_permission(permission_code)
    WHERE permission_code IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_iam_perm_parent ON iam_permission(parent_id);

-- ---------- 用户-角色 ----------
CREATE TABLE IF NOT EXISTS iam_user_role (
    user_id VARCHAR(32) NOT NULL,
    role_id VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

-- ---------- 角色-权限（permission_id 即权限编码） ----------
CREATE TABLE IF NOT EXISTS iam_role_permission (
    role_id       VARCHAR(32)  NOT NULL,
    permission_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (role_id, permission_id)
);
CREATE INDEX IF NOT EXISTS idx_iam_rp_perm ON iam_role_permission(permission_id);

-- ---------- 组织树（ZONE/COMPANY/DEPT/GROUP） ----------
CREATE TABLE IF NOT EXISTS iam_org (
    id         VARCHAR(32)  PRIMARY KEY,
    parent_id  VARCHAR(32),
    org_type   VARCHAR(16)  NOT NULL,
    org_code   VARCHAR(32)  NOT NULL,
    org_name   VARCHAR(128) NOT NULL,
    path       VARCHAR(512),
    sort_order INTEGER      NOT NULL DEFAULT 0,
    status     VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_org IS '四级组织树：ZONE/COMPANY/DEPT/GROUP';
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_org_code ON iam_org(org_code);
CREATE INDEX IF NOT EXISTS idx_iam_org_parent ON iam_org(parent_id);

-- ============================================================================
-- 跨服务只读授权：SYS 后端本地校验 IAM 签发的 JWT 后，从 IAM 库读取用户角色权限，
-- SYS 不维护第二套用户表（计划 §17.2）。
-- ============================================================================
GRANT USAGE ON SCHEMA iam TO sys_app;
GRANT SELECT ON iam_user, iam_role, iam_permission,
    iam_user_role, iam_role_permission, iam_org TO sys_app;

-- IAM application account: full DML on its own schema
GRANT USAGE ON SCHEMA iam TO iam_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA iam TO iam_app;
