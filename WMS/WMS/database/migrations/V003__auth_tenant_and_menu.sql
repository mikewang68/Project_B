CREATE TABLE IF NOT EXISTS auth_company (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_auth_company_status CHECK (status IN ('ENABLED', 'DISABLED', 'DELETED'))
);

CREATE TABLE IF NOT EXISTS auth_user (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    username VARCHAR(64) NOT NULL,
    mobile VARCHAR(32),
    display_name VARCHAR(128) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    login_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    password_change_required BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_auth_user_company_username UNIQUE (company_id, username),
    CONSTRAINT ck_auth_user_status CHECK (status IN ('ENABLED', 'DISABLED', 'DELETED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_auth_user_company_mobile
    ON auth_user(company_id, mobile) WHERE mobile IS NOT NULL;

CREATE TABLE IF NOT EXISTS auth_role (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    legacy_code VARCHAR(64),
    sort_order INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS auth_permission (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(128) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_user_role (
    user_id BIGINT NOT NULL REFERENCES auth_user(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES auth_role(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS auth_role_permission (
    role_id BIGINT NOT NULL REFERENCES auth_role(id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES auth_permission(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE IF NOT EXISTS wms_warehouse (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wms_warehouse_company_code UNIQUE (company_id, code),
    CONSTRAINT ck_wms_warehouse_status CHECK (status IN ('ENABLED', 'DISABLED', 'DELETED'))
);

CREATE TABLE IF NOT EXISTS wms_owner (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wms_owner_company_code UNIQUE (company_id, code),
    CONSTRAINT ck_wms_owner_status CHECK (status IN ('ENABLED', 'DISABLED', 'DELETED'))
);

CREATE TABLE IF NOT EXISTS auth_user_warehouse (
    user_id BIGINT NOT NULL REFERENCES auth_user(id) ON DELETE CASCADE,
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, warehouse_id)
);

CREATE TABLE IF NOT EXISTS auth_user_owner (
    user_id BIGINT NOT NULL REFERENCES auth_user(id) ON DELETE CASCADE,
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, owner_id)
);

CREATE TABLE IF NOT EXISTS auth_menu (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    parent_code VARCHAR(64),
    name VARCHAR(64) NOT NULL,
    path VARCHAR(128) NOT NULL,
    icon VARCHAR(64),
    required_permission VARCHAR(128),
    sort_order INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO auth_company(code, name)
SELECT 'default', '默认公司'
WHERE NOT EXISTS (SELECT 1 FROM auth_company WHERE code = 'default');

INSERT INTO wms_warehouse(company_id, code, name)
SELECT id, 'default', '默认仓库' FROM auth_company WHERE code = 'default'
  AND NOT EXISTS (
      SELECT 1 FROM wms_warehouse w
      JOIN auth_company c ON c.id = w.company_id
      WHERE c.code = 'default' AND w.code = 'default'
  );

INSERT INTO wms_owner(company_id, code, name)
SELECT id, 'default', '默认货主' FROM auth_company WHERE code = 'default'
  AND NOT EXISTS (
      SELECT 1 FROM wms_owner o
      JOIN auth_company c ON c.id = o.company_id
      WHERE c.code = 'default' AND o.code = 'default'
  );

INSERT INTO auth_role(code, name, legacy_code, sort_order)
SELECT 'ADMIN', '系统管理员', 'admin', 10 WHERE NOT EXISTS (SELECT 1 FROM auth_role WHERE code = 'ADMIN');
INSERT INTO auth_role(code, name, legacy_code, sort_order)
SELECT 'MANAGER', '仓储管理员', 'manager', 20 WHERE NOT EXISTS (SELECT 1 FROM auth_role WHERE code = 'MANAGER');
INSERT INTO auth_role(code, name, legacy_code, sort_order)
SELECT 'NORMAL', '普通用户', 'normal', 30 WHERE NOT EXISTS (SELECT 1 FROM auth_role WHERE code = 'NORMAL');

INSERT INTO auth_permission(code, name)
SELECT 'dashboard:view', '查看仓储工作台' WHERE NOT EXISTS (SELECT 1 FROM auth_permission WHERE code = 'dashboard:view');
INSERT INTO auth_permission(code, name)
SELECT 'inventory:read', '查询库存' WHERE NOT EXISTS (SELECT 1 FROM auth_permission WHERE code = 'inventory:read');
INSERT INTO auth_permission(code, name)
SELECT 'tenant:switch', '切换已分配仓库和货主' WHERE NOT EXISTS (SELECT 1 FROM auth_permission WHERE code = 'tenant:switch');
INSERT INTO auth_permission(code, name)
SELECT 'tenant:all', '访问公司全部仓库和货主' WHERE NOT EXISTS (SELECT 1 FROM auth_permission WHERE code = 'tenant:all');
INSERT INTO auth_permission(code, name)
SELECT 'system:user:read', '查看用户' WHERE NOT EXISTS (SELECT 1 FROM auth_permission WHERE code = 'system:user:read');
INSERT INTO auth_permission(code, name)
SELECT 'system:user:write', '维护用户' WHERE NOT EXISTS (SELECT 1 FROM auth_permission WHERE code = 'system:user:write');

INSERT INTO auth_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM auth_role r CROSS JOIN auth_permission p
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (SELECT 1 FROM auth_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

INSERT INTO auth_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM auth_role r CROSS JOIN auth_permission p
WHERE r.code = 'MANAGER' AND p.code IN ('dashboard:view', 'inventory:read', 'tenant:switch', 'tenant:all', 'system:user:read', 'system:user:write')
  AND NOT EXISTS (SELECT 1 FROM auth_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

INSERT INTO auth_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM auth_role r CROSS JOIN auth_permission p
WHERE r.code = 'NORMAL' AND p.code IN ('dashboard:view', 'inventory:read', 'tenant:switch')
  AND NOT EXISTS (SELECT 1 FROM auth_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- 本地阶段验收账号：admin / Admin@123456。首次登录后必须修改密码。
INSERT INTO auth_user(company_id, username, display_name, password_hash, password_change_required)
SELECT id, 'admin', '本地管理员', '$2a$12$nwO2QEoxTQBkFbm0HD3sG.qWqrVb1/bn4N4/luqXvERiuG4OxYTC.', TRUE
FROM auth_company WHERE code = 'default'
  AND NOT EXISTS (
      SELECT 1 FROM auth_user u JOIN auth_company c ON c.id = u.company_id
      WHERE c.code = 'default' AND u.username = 'admin'
  );

INSERT INTO auth_user_role(user_id, role_id)
SELECT u.id, r.id FROM auth_user u
JOIN auth_company c ON c.id = u.company_id
CROSS JOIN auth_role r
WHERE c.code = 'default' AND u.username = 'admin' AND r.code = 'ADMIN'
  AND NOT EXISTS (SELECT 1 FROM auth_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

INSERT INTO auth_user_warehouse(user_id, warehouse_id)
SELECT u.id, w.id FROM auth_user u
JOIN auth_company c ON c.id = u.company_id
JOIN wms_warehouse w ON w.company_id = c.id AND w.code = 'default'
WHERE c.code = 'default' AND u.username = 'admin'
  AND NOT EXISTS (SELECT 1 FROM auth_user_warehouse uw WHERE uw.user_id = u.id AND uw.warehouse_id = w.id);

INSERT INTO auth_user_owner(user_id, owner_id)
SELECT u.id, o.id FROM auth_user u
JOIN auth_company c ON c.id = u.company_id
JOIN wms_owner o ON o.company_id = c.id AND o.code = 'default'
WHERE c.code = 'default' AND u.username = 'admin'
  AND NOT EXISTS (SELECT 1 FROM auth_user_owner uo WHERE uo.user_id = u.id AND uo.owner_id = o.id);

INSERT INTO auth_menu(code, name, path, icon, required_permission, sort_order)
SELECT 'dashboard', '仓储工作台', '/', 'DataBoard', 'dashboard:view', 10
WHERE NOT EXISTS (SELECT 1 FROM auth_menu WHERE code = 'dashboard');
INSERT INTO auth_menu(code, name, path, icon, required_permission, sort_order)
SELECT 'inventory', '库存查询', '/inventory', 'Box', 'inventory:read', 20
WHERE NOT EXISTS (SELECT 1 FROM auth_menu WHERE code = 'inventory');
INSERT INTO auth_menu(code, name, path, icon, required_permission, sort_order)
SELECT 'users', '用户管理', '/system/users', 'User', 'system:user:read', 90
WHERE NOT EXISTS (SELECT 1 FROM auth_menu WHERE code = 'users');

CREATE INDEX IF NOT EXISTS idx_auth_user_company_status ON auth_user(company_id, status);
CREATE INDEX IF NOT EXISTS idx_wms_warehouse_company_status ON wms_warehouse(company_id, status);
CREATE INDEX IF NOT EXISTS idx_wms_owner_company_status ON wms_owner(company_id, status);
