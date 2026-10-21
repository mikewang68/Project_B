-- =============================================================================
-- 009-sys-permissions.sql — SYS permission catalog for the isolated demo
--
-- Why this file exists
--   005-permissions.sql seeds the IAM permission tree and the TRUST catalog but
--   contains no `sys:*` entries. The isolated demo's auditor and scoped_user
--   roles reference SYS permissions, so a rebuilt environment fails to grant
--   them (and the SYS pages show nothing). This file adds the SYS catalog and
--   the single missing IAM menu permission, copied verbatim from the running
--   isolated database (ids, parents, routes, sort order, status).
--
-- Safety
--   - Idempotent: every row is guarded by WHERE NOT EXISTS on the primary key.
--   - Adds permission CATALOG rows only. It grants nothing to any role; role
--     grants are in 008-demo-roles.sql.
--   - No credentials, no password hashes.
--   - ISOLATED demo database only.
-- =============================================================================

SET search_path TO iam, public;
BEGIN;

-- ---------------------------------------------------------------------------
-- 1. SYS permission tree: system -> menus -> buttons
-- ---------------------------------------------------------------------------
INSERT INTO iam_permission (id, parent_id, node_type, permission_code, permission_name, system_code, route, icon, sort_order, status)
SELECT v.id, v.parent_id, v.node_type, v.code, v.name, 'sys', v.route, v.icon, v.sort_order, 'active'
  FROM (VALUES
    ('sys',                   NULL::varchar, 'SYSTEM', NULL::varchar,              '系统设置与维护', NULL::varchar,            'Setting', 40),
    ('sys-dict',              'sys',         'MENU',   NULL,                       '数据字典',       '/sys/maintain/dict',     NULL,      50),
    ('sys:dict:type:view',    'sys-dict',    'BUTTON', 'sys:dict:type:view',       '数据字典-查看',   NULL,                     NULL,      51),
    ('sys:dict:type:add',     'sys-dict',    'BUTTON', 'sys:dict:type:add',        '字典分类-新增',   NULL,                     NULL,      52),
    ('sys:dict:item:view',    'sys-dict',    'BUTTON', 'sys:dict:item:view',       '字典项-查看',     NULL,                     NULL,      53),
    ('sys:dict:item:add',     'sys-dict',    'BUTTON', 'sys:dict:item:add',        '字典项-新增',     NULL,                     NULL,      54),
    ('sys:dict:item:edit',    'sys-dict',    'BUTTON', 'sys:dict:item:edit',       '字典项-编辑',     NULL,                     NULL,      55),
    ('sys:dict:item:delete',  'sys-dict',    'BUTTON', 'sys:dict:item:delete',     '字典项-删除',     NULL,                     NULL,      56),
    ('sys-log',               'sys',         'MENU',   NULL,                       '操作日志',       '/sys/maintain/logs',     NULL,      60),
    ('sys:log:list:view',     'sys-log',     'BUTTON', 'sys:log:list:view',        '操作日志-查看',   NULL,                     NULL,      61),
    ('sys-config',            'sys',         'MENU',   NULL,                       '系统配置',       '/sys/maintain/config',   NULL,      70),
    ('sys:config:list:view',  'sys-config',  'BUTTON', 'sys:config:list:view',     '系统配置-查看',   NULL,                     NULL,      71),
    ('sys:config:list:edit',  'sys-config',  'BUTTON', 'sys:config:list:edit',     '系统配置-编辑',   NULL,                     NULL,      72)
  ) AS v(id, parent_id, node_type, code, name, route, icon, sort_order)
 WHERE NOT EXISTS (SELECT 1 FROM iam_permission p WHERE p.id = v.id);

-- ---------------------------------------------------------------------------
-- 2. The one IAM menu permission the auditor role also needs
-- ---------------------------------------------------------------------------
INSERT INTO iam_permission (id, parent_id, node_type, permission_code, permission_name, system_code, route, icon, sort_order, status)
SELECT 'iam:menu:tree:view', 'iam-menu', 'BUTTON', 'iam:menu:tree:view',
       '菜单权限树-查看', 'iam', NULL, NULL, 31, 'active'
 WHERE NOT EXISTS (SELECT 1 FROM iam_permission WHERE id = 'iam:menu:tree:view')
   AND EXISTS (SELECT 1 FROM iam_permission WHERE id = 'iam-menu');

-- ---------------------------------------------------------------------------
-- 3. Post-conditions
-- ---------------------------------------------------------------------------
DO $$
DECLARE n_sys int; n_menu int;
BEGIN
  SELECT count(*) INTO n_sys FROM iam_permission WHERE system_code = 'sys';
  SELECT count(*) INTO n_menu FROM iam_permission WHERE id = 'iam:menu:tree:view';

  IF n_sys <> 13 THEN RAISE EXCEPTION 'expected 13 sys permission rows, got %', n_sys; END IF;
  IF n_menu <> 1 THEN RAISE EXCEPTION 'iam:menu:tree:view was not created'; END IF;
END $$;

COMMIT;
