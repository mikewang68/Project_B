-- =============================================================================
-- Project_B 阶段 2-B 第一批受控测试种子脚本：007-navigation-seed-batch1.sql
-- 范围：8 个纳管系统应用元数据 + DTS 历史保留系统 + Batch 1 七个功能页面 (IAM 4 + SYS 3)
-- 严格限制：除雷文负责的 Batch 1 七页外，其余六系统 100 个候选页保持未放行态
-- =============================================================================

BEGIN;

SET LOCAL search_path TO iam, public;

-- 1. 八系统及 DTS 历史保留系统元数据
INSERT INTO iam.iam_navigation_app (
    app_code, iam_system_code, portal_module_key, source_app_code, display_name,
    nav_group, sort_order, entry_base_path, in_nav_scope, enabled, valid,
    mapping_status, approval_status, content_sha256, row_version, last_operation_source, updated_by, notes
) VALUES
('WMS',   'wms',     'wms',     'WMS',   '仓储管理系统',         'BUSINESS', 10, '/wms/',   1, 1, 1, 'VERIFIED_EXISTING',   'APPROVED',         'sha256_wms',   1, 'IMPORT_TOOL', 'SYSTEM_STAGE2B', '周绪敬负责'),
('PSMS',  'pps',     'psms',    'PSMS',  '生产调度系统',         'BUSINESS', 20, '/psms/',  1, 1, 1, 'VERIFIED_EXISTING',   'APPROVED',         'sha256_psms',  1, 'IMPORT_TOOL', 'SYSTEM_STAGE2B', 'PSMS'),
('SCS',   'safety',  'scs',     'SCS',   '安全安防系统',         'BUSINESS', 30, '/scs/',   1, 1, 1, 'VERIFIED_EXISTING',   'APPROVED',         'sha256_scs',   1, 'IMPORT_TOOL', 'SYSTEM_STAGE2B', 'SCS'),
('EHMS',  'ehealth', 'ehm',     'EHMS',  '设备健康管理系统',     'BUSINESS', 40, '/ehm/',   1, 1, 1, 'VERIFIED_EXISTING',   'APPROVED',         'sha256_ehms',  1, 'IMPORT_TOOL', 'SYSTEM_STAGE2B', '闫鑫钰负责'),
('EMCS',  'ems',     'emcs',    'EMCS',  '能源管理控制系统',     'BUSINESS', 50, '/emcs/',  1, 1, 1, 'VERIFIED_EXISTING',   'APPROVED',         'sha256_emcs',  1, 'IMPORT_TOOL', 'SYSTEM_STAGE2B', 'EMCS'),
('TRUST', 'trust',   'trust',   'TRUST', '可信存证与身份系统',   'BUSINESS', 60, '/trust/', 1, 1, 1, 'VERIFIED_EXISTING',   'APPROVED',         'sha256_trust', 1, 'IMPORT_TOOL', 'SYSTEM_STAGE2B', 'TRUST'),
('IAM',   'iam',     'iam',     'IAM',   '统一身份与权限管理系统', 'COMMON',   70, '/iam/',   1, 1, 1, 'VERIFIED_EXISTING',   'APPROVED',         'sha256_iam',   1, 'IMPORT_TOOL', 'SYSTEM_STAGE2B', '雷文负责 (Batch 1)'),
('SYS',   'sys',     'sys',     'SYS',   '系统管理系统',         'COMMON',   80, '/sys/',   1, 1, 1, 'VERIFIED_EXISTING',   'APPROVED',         'sha256_sys',   1, 'IMPORT_TOOL', 'SYSTEM_STAGE2B', '雷文负责 (Batch 1)'),
('DTS',   'dt',      'dts',     'DTS',   '历史数据传输系统',     'EXCLUDED', 999, '',       0, 0, 1, 'EXCLUDED_LEGACY',     'PENDING_DECISION', 'sha256_dts',   1, 'IMPORT_TOOL', 'SYSTEM_STAGE2B', '已排除历史系统，保留权限记录')
ON CONFLICT (app_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    sort_order = EXCLUDED.sort_order,
    in_nav_scope = EXCLUDED.in_nav_scope,
    updated_at = CURRENT_TIMESTAMP;

-- 2. 根节点 (APP:IAM, APP:SYS)
INSERT INTO iam.iam_navigation_menu (
    stable_menu_key, navigation_app_code, source_app_code, source_menu_id, parent_stable_menu_key,
    hierarchy_code, original_level, normalized_level, sibling_sort_order, menu_name,
    menu_kind, entry_mode, visible, enabled, valid, nav_eligible, source_reference, content_sha256, last_batch_id
) VALUES
('APP:IAM', 'IAM', 'IAM', 'iam', NULL, 'IAM', 1, 1, 1, '统一身份与权限管理系统', 'APP', 'NONE', 1, 1, 1, 0, 'CSV:IAM', 'sha_app_iam', 'BATCH_20261013_B1'),
('APP:SYS', 'SYS', 'SYS', 'sys', NULL, 'SYS', 1, 1, 1, '系统管理系统',         'APP', 'NONE', 1, 1, 1, 0, 'CSV:SYS', 'sha_app_sys', 'BATCH_20261013_B1')
ON CONFLICT (stable_menu_key) DO NOTHING;

-- 3. Batch 1 七个静态候选页面 (IAM 4 + SYS 3)
INSERT INTO iam.iam_navigation_menu (
    stable_menu_key, navigation_app_code, source_app_code, source_menu_id, parent_stable_menu_key,
    hierarchy_code, original_level, normalized_level, sibling_sort_order, menu_name,
    menu_kind, entry_mode, route_path, entry_path, page_key, tab_key,
    visible, enabled, valid, nav_eligible, source_reference, content_sha256, last_batch_id
) VALUES
('IAM:users',  'IAM', 'IAM', 'users',  'APP:IAM', 'IAM.01', 2, 2, 10, '用户管理',   'PAGE', 'SPA_ROUTE', '/system/users',    '/iam/system/users',    '', '', 1, 1, 1, 1, 'CSV:IAM', 'sha_iam_users',  'BATCH_20261013_B1'),
('IAM:roles',  'IAM', 'IAM', 'roles',  'APP:IAM', 'IAM.02', 2, 2, 20, '角色管理',   'PAGE', 'SPA_ROUTE', '/system/roles',    '/iam/system/roles',    '', '', 1, 1, 1, 1, 'CSV:IAM', 'sha_iam_roles',  'BATCH_20261013_B1'),
('IAM:menus',  'IAM', 'IAM', 'menus',  'APP:IAM', 'IAM.03', 2, 2, 30, '菜单与权限', 'PAGE', 'SPA_ROUTE', '/system/menus',    '/iam/system/menus',    '', '', 1, 1, 1, 1, 'CSV:IAM', 'sha_iam_menus',  'BATCH_20261013_B1'),
('IAM:health', 'IAM', 'IAM', 'health', 'APP:IAM', 'IAM.04', 2, 2, 40, '权限健康',   'PAGE', 'SPA_ROUTE', '/system/health',   '/iam/system/health',   '', '', 1, 1, 1, 1, 'CSV:IAM', 'sha_iam_health', 'BATCH_20261013_B1'),
('SYS:dict',   'SYS', 'SYS', 'dict',   'APP:SYS', 'SYS.01', 2, 2, 10, '数据字典',   'PAGE', 'SPA_ROUTE', '/maintain/dict',   '/sys/maintain/dict',   '', '', 1, 1, 1, 1, 'CSV:SYS', 'sha_sys_dict',   'BATCH_20261013_B1'),
('SYS:logs',   'SYS', 'SYS', 'logs',   'APP:SYS', 'SYS.02', 2, 2, 20, '操作日志',   'PAGE', 'SPA_ROUTE', '/maintain/logs',   '/sys/maintain/logs',   '', '', 1, 1, 1, 1, 'CSV:SYS', 'sha_sys_logs',   'BATCH_20261013_B1'),
('SYS:config', 'SYS', 'SYS', 'config', 'APP:SYS', 'SYS.03', 2, 2, 30, '系统配置',   'PAGE', 'SPA_ROUTE', '/maintain/config', '/sys/maintain/config', '', '', 1, 1, 1, 1, 'CSV:SYS', 'sha_sys_config', 'BATCH_20261013_B1')
ON CONFLICT (stable_menu_key) DO UPDATE SET
    menu_name = EXCLUDED.menu_name,
    route_path = EXCLUDED.route_path,
    entry_path = EXCLUDED.entry_path,
    nav_eligible = EXCLUDED.nav_eligible,
    updated_at = CURRENT_TIMESTAMP;

-- 4. Batch 1 七个页面权限绑定规则
INSERT INTO iam.iam_navigation_menu_permission (
    mapping_id, stable_menu_key, navigation_app_code, permission_scope,
    candidate_permission_code, existing_iam_system_code, match_kind, mapping_status, approval_status,
    valid, content_sha256, last_batch_id
) VALUES
('MAP:IAM:users:VIEW',  'IAM:users',  'IAM', 'VIEW', 'iam:user:list:view', 'iam', 'EXACT', 'VERIFIED', 'APPROVED', 1, 'sha_map_iam_users',  'BATCH_20261013_B1'),
('MAP:IAM:roles:VIEW',  'IAM:roles',  'IAM', 'VIEW', 'iam:role:list:view', 'iam', 'EXACT', 'VERIFIED', 'APPROVED', 1, 'sha_map_iam_roles',  'BATCH_20261013_B1'),
('MAP:IAM:menus:VIEW',  'IAM:menus',  'IAM', 'VIEW', 'iam:menu:tree:view', 'iam', 'EXACT', 'VERIFIED', 'APPROVED', 1, 'sha_map_iam_menus',  'BATCH_20261013_B1'),
('MAP:IAM:health:VIEW', 'IAM:health', 'IAM', 'VIEW', 'iam:role:list:view', 'iam', 'EXACT', 'VERIFIED', 'APPROVED', 1, 'sha_map_iam_health', 'BATCH_20261013_B1'),
('MAP:SYS:dict:VIEW',   'SYS:dict',   'SYS', 'VIEW', 'sys:dict:type:view', 'sys', 'EXACT', 'VERIFIED', 'APPROVED', 1, 'sha_map_sys_dict',   'BATCH_20261013_B1'),
('MAP:SYS:logs:VIEW',   'SYS:logs',   'SYS', 'VIEW', 'sys:log:list:view',  'sys', 'EXACT', 'VERIFIED', 'APPROVED', 1, 'sha_map_sys_logs',   'BATCH_20261013_B1'),
('MAP:SYS:config:VIEW', 'SYS:config', 'SYS', 'VIEW', 'sys:config:list:view','sys','EXACT', 'VERIFIED', 'APPROVED', 1, 'sha_map_sys_config', 'BATCH_20261013_B1')
ON CONFLICT (mapping_id) DO UPDATE SET
    candidate_permission_code = EXCLUDED.candidate_permission_code,
    approval_status = EXCLUDED.approval_status,
    updated_at = CURRENT_TIMESTAMP;

COMMIT;
