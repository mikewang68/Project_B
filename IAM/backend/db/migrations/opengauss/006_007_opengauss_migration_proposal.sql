-- =============================================================================
-- Project_B openGauss 迁移提案：006_007_opengauss_migration_proposal.sql
-- 提案状态: PROPOSAL_ONLY (受版本化管理适配补丁，待用户独立验收后由 DBA 部署执行)
-- 适用模式: openGauss 6.0.5 (PG 兼容模式 / B 兼容模式适配)
-- 包含组件:
--   1. 六张权威导航主表定义与防环约束 (006-navigation-schema)
--   2. 8 个纳管系统 + DTS 历史保留系统元数据 (007-navigation-seed-batch1)
--   3. Batch 1 首批七个功能页面 (IAM 4 + SYS 3) 及映射权限
-- 注意: 回滚方案位于独立文件 006_007_opengauss_batch1_rollback_proposal.sql，本文件末尾严禁携带 rollback
-- =============================================================================

BEGIN;

CREATE SCHEMA IF NOT EXISTS iam;
SET LOCAL search_path TO iam, public;

-- -----------------------------------------------------------------------------
-- 1. iam_navigation_app：系统元数据表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS iam.iam_navigation_app (
    app_code                VARCHAR(32)  PRIMARY KEY,
    iam_system_code         VARCHAR(32)  NOT NULL UNIQUE,
    portal_module_key       VARCHAR(32)  NOT NULL UNIQUE,
    source_app_code         VARCHAR(64)  NOT NULL,
    display_name            VARCHAR(128) NOT NULL,
    nav_group               VARCHAR(32)  NOT NULL CHECK (nav_group IN ('BUSINESS', 'COMMON', 'EXCLUDED')),
    sort_order              INTEGER      NOT NULL DEFAULT 100,
    entry_base_path         VARCHAR(255) NOT NULL DEFAULT '',
    in_nav_scope            SMALLINT     NOT NULL DEFAULT 1 CHECK (in_nav_scope IN (0, 1)),
    enabled                 SMALLINT     NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    valid                   SMALLINT     NOT NULL DEFAULT 1 CHECK (valid IN (0, 1)),
    mapping_status          VARCHAR(32)  NOT NULL CHECK (mapping_status IN ('VERIFIED_EXISTING', 'PROPOSED_NEW', 'PENDING_CONFIRMATION', 'EXCLUDED_LEGACY')),
    approval_status         VARCHAR(32)  NOT NULL DEFAULT 'PENDING_DECISION' CHECK (approval_status IN ('PENDING_DECISION', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    content_sha256          VARCHAR(64)  NOT NULL,
    row_version             INTEGER      NOT NULL DEFAULT 1 CHECK (row_version >= 1),
    last_operation_source   VARCHAR(32)  NOT NULL DEFAULT 'IMPORT_TOOL' CHECK (last_operation_source IN ('IMPORT_TOOL', 'ADMIN_UI', 'ROLLBACK_TOOL', 'SYSTEM_MIGRATION')),
    updated_by              VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM_STAGE2B',
    last_batch_id           VARCHAR(64)  NULL,
    previous_revision_json  JSONB        NULL,
    notes                   TEXT         NOT NULL DEFAULT '',
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_iam_nav_app_active_sort
    ON iam.iam_navigation_app (sort_order)
    WHERE valid = 1 AND in_nav_scope = 1;

-- -----------------------------------------------------------------------------
-- 2. iam_navigation_menu：完整层级菜单主表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS iam.iam_navigation_menu (
    stable_menu_key         VARCHAR(128) PRIMARY KEY,
    navigation_app_code     VARCHAR(32)  NOT NULL REFERENCES iam.iam_navigation_app(app_code),
    source_app_code         VARCHAR(64)  NOT NULL,
    source_menu_id          VARCHAR(128) NOT NULL,
    source_parent_id        VARCHAR(128) NOT NULL DEFAULT '',
    parent_stable_menu_key  VARCHAR(128) NULL REFERENCES iam.iam_navigation_menu(stable_menu_key),
    hierarchy_code          VARCHAR(64)  NOT NULL,
    original_level          INTEGER      NOT NULL DEFAULT 1,
    normalized_level        INTEGER      NOT NULL CHECK (normalized_level >= 1 AND normalized_level <= 6),
    sibling_sort_order      INTEGER      NOT NULL DEFAULT 1,
    menu_name               VARCHAR(128) NOT NULL,
    projected_display_title VARCHAR(255) NOT NULL DEFAULT '',
    menu_kind               VARCHAR(32)  NOT NULL CHECK (menu_kind IN ('APP', 'DIRECTORY', 'PAGE', 'AUXILIARY', 'DRAWER', 'PLANNED')),
    entry_mode              VARCHAR(32)  NOT NULL CHECK (entry_mode IN ('SPA_ROUTE', 'PAGE_KEY', 'TAB', 'EXTERNAL_ROUTE', 'DRAWER', 'NONE', 'UNRESOLVED')),
    route_path              VARCHAR(255) NOT NULL DEFAULT '',
    entry_path              VARCHAR(255) NOT NULL DEFAULT '',
    page_key                VARCHAR(128) NOT NULL DEFAULT '',
    tab_key                 VARCHAR(128) NOT NULL DEFAULT '',
    component_path          VARCHAR(255) NOT NULL DEFAULT '',
    icon                    VARCHAR(64)  NOT NULL DEFAULT '',
    group_label             VARCHAR(128) NOT NULL DEFAULT '',
    visible                 SMALLINT     NOT NULL DEFAULT 1 CHECK (visible IN (0, 1)),
    enabled                 SMALLINT     NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    valid                   SMALLINT     NOT NULL DEFAULT 1 CHECK (valid IN (0, 1)),
    implementation_status   VARCHAR(64)  NOT NULL DEFAULT 'UNKNOWN',
    source_stage_note       VARCHAR(128) NOT NULL DEFAULT '',
    nav_eligible            SMALLINT     NOT NULL DEFAULT 0 CHECK (nav_eligible IN (0, 1)),
    nav_exclusion_reason    VARCHAR(255) NOT NULL DEFAULT '',
    source_permission_raw   VARCHAR(255) NOT NULL DEFAULT '',
    source_reference        VARCHAR(512) NOT NULL,
    content_sha256          VARCHAR(64)  NOT NULL,
    row_version             INTEGER      NOT NULL DEFAULT 1 CHECK (row_version >= 1),
    last_operation_source   VARCHAR(32)  NOT NULL DEFAULT 'IMPORT_TOOL' CHECK (last_operation_source IN ('IMPORT_TOOL', 'ADMIN_UI', 'ROLLBACK_TOOL', 'SYSTEM_MIGRATION')),
    updated_by              VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM_STAGE2B',
    last_batch_id           VARCHAR(64)  NOT NULL,
    previous_revision_json  JSONB        NULL,
    notes                   TEXT         NOT NULL DEFAULT '',
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_iam_nav_menu_root_parent_v2 CHECK (
        (menu_kind = 'APP' AND parent_stable_menu_key IS NULL AND normalized_level = 1)
        OR (menu_kind <> 'APP' AND parent_stable_menu_key IS NOT NULL AND normalized_level >= 2)
    ),
    CONSTRAINT chk_iam_nav_menu_no_self_parent_v2 CHECK (
        parent_stable_menu_key IS NULL OR parent_stable_menu_key <> stable_menu_key
    ),
    CONSTRAINT chk_iam_nav_menu_6gates_orthogonality_v2 CHECK (
        (nav_eligible = 1 AND menu_kind = 'PAGE' AND visible = 1 AND enabled = 1 AND valid = 1 AND navigation_app_code <> 'DTS' AND entry_mode IN ('SPA_ROUTE', 'PAGE_KEY', 'TAB'))
        OR (nav_eligible = 0)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_iam_nav_menu_active_hcode_v2
    ON iam.iam_navigation_menu (hierarchy_code)
    WHERE valid = 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_iam_nav_menu_sibling_sort_v2
    ON iam.iam_navigation_menu (navigation_app_code, COALESCE(parent_stable_menu_key, '__ROOT__'), normalized_level, sibling_sort_order)
    WHERE valid = 1;

CREATE INDEX IF NOT EXISTS idx_iam_nav_menu_projection_v2
    ON iam.iam_navigation_menu (navigation_app_code, valid, enabled, visible, nav_eligible, sibling_sort_order);

-- 防多跳环路与跨系统触发器
CREATE OR REPLACE FUNCTION iam.fn_check_nav_menu_hierarchy_v2()
RETURNS TRIGGER AS $$
DECLARE
    v_parent_app VARCHAR(32);
    v_parent_lvl INTEGER;
    v_curr_key   VARCHAR(128);
    v_depth      INTEGER := 0;
BEGIN
    IF NEW.parent_stable_menu_key IS NOT NULL THEN
        SELECT navigation_app_code, normalized_level
          INTO v_parent_app, v_parent_lvl
          FROM iam.iam_navigation_menu
         WHERE stable_menu_key = NEW.parent_stable_menu_key;

        IF v_parent_app IS NULL THEN
            RAISE EXCEPTION 'Parent menu % does not exist', NEW.parent_stable_menu_key;
        END IF;

        IF v_parent_app <> NEW.navigation_app_code THEN
            RAISE EXCEPTION 'Cross-system parent reference forbidden: child % (%) -> parent % (%)',
                NEW.stable_menu_key, NEW.navigation_app_code, NEW.parent_stable_menu_key, v_parent_app;
        END IF;

        IF NEW.normalized_level <> v_parent_lvl + 1 THEN
            RAISE EXCEPTION 'Level discontinuity at %: child level % != parent level % + 1',
                NEW.stable_menu_key, NEW.normalized_level, v_parent_lvl;
        END IF;

        v_curr_key := NEW.parent_stable_menu_key;
        WHILE v_curr_key IS NOT NULL LOOP
            IF v_curr_key = NEW.stable_menu_key THEN
                RAISE EXCEPTION 'Multi-hop hierarchy cycle detected at menu %', NEW.stable_menu_key;
            END IF;
            v_depth := v_depth + 1;
            IF v_depth > 10 THEN
                RAISE EXCEPTION 'Hierarchy depth exceeded safety limit (10) at menu %', NEW.stable_menu_key;
            END IF;
            SELECT parent_stable_menu_key
              INTO v_curr_key
              FROM iam.iam_navigation_menu
             WHERE stable_menu_key = v_curr_key;
        END LOOP;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_check_nav_menu_hierarchy_v2 ON iam.iam_navigation_menu;
CREATE TRIGGER trg_check_nav_menu_hierarchy_v2
    BEFORE INSERT OR UPDATE ON iam.iam_navigation_menu
    FOR EACH ROW EXECUTE PROCEDURE iam.fn_check_nav_menu_hierarchy_v2();

-- -----------------------------------------------------------------------------
-- 3. iam_navigation_menu_permission：菜单与权限关联表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS iam.iam_navigation_menu_permission (
    mapping_id                 VARCHAR(160) PRIMARY KEY,
    stable_menu_key            VARCHAR(128) NOT NULL REFERENCES iam.iam_navigation_menu(stable_menu_key),
    navigation_app_code        VARCHAR(32)  NOT NULL REFERENCES iam.iam_navigation_app(app_code),
    permission_scope           VARCHAR(32)  NOT NULL DEFAULT 'VIEW' CHECK (permission_scope IN ('VIEW', 'OPERATION_REF')),
    inter_group_operator       VARCHAR(16)  NOT NULL DEFAULT 'ALL_GROUPS' CHECK (inter_group_operator IN ('ALL_GROUPS', 'ANY_GROUP')),
    rule_group_no              INTEGER      NOT NULL DEFAULT 1 CHECK (rule_group_no >= 1),
    rule_operator              VARCHAR(16)  NOT NULL DEFAULT 'SINGLE' CHECK (rule_operator IN ('SINGLE', 'ANY_OF', 'ALL_OF', 'NONE')),
    condition_seq              INTEGER      NOT NULL DEFAULT 1 CHECK (condition_seq >= 1),
    source_permission_raw      VARCHAR(255) NOT NULL DEFAULT '',
    candidate_permission_code  VARCHAR(128) NOT NULL DEFAULT '',
    existing_iam_permission_id VARCHAR(128) NULL,
    existing_iam_system_code   VARCHAR(32)  NOT NULL DEFAULT '',
    match_kind                 VARCHAR(32)  NOT NULL CHECK (match_kind IN ('EXACT', 'CODE_ALIAS', 'SEMANTIC_PROPOSAL', 'NO_MATCH', 'CONFLICT')),
    mapping_status             VARCHAR(32)  NOT NULL CHECK (mapping_status IN ('VERIFIED', 'PROPOSED', 'MISSING', 'NEEDS_CONFIRMATION')),
    approval_status            VARCHAR(32)  NOT NULL DEFAULT 'PENDING_DECISION' CHECK (approval_status IN ('PENDING_DECISION', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    requires_readonly_design   SMALLINT     NOT NULL DEFAULT 0 CHECK (requires_readonly_design IN (0, 1)),
    valid                      SMALLINT     NOT NULL DEFAULT 1 CHECK (valid IN (0, 1)),
    source_evidence            VARCHAR(512) NOT NULL DEFAULT '',
    current_iam_evidence       VARCHAR(512) NOT NULL DEFAULT '',
    risk_note                  TEXT         NOT NULL DEFAULT '',
    content_sha256             VARCHAR(64)  NOT NULL,
    row_version                INTEGER      NOT NULL DEFAULT 1 CHECK (row_version >= 1),
    last_operation_source      VARCHAR(32)  NOT NULL DEFAULT 'IMPORT_TOOL' CHECK (last_operation_source IN ('IMPORT_TOOL', 'ADMIN_UI', 'ROLLBACK_TOOL', 'SYSTEM_MIGRATION')),
    updated_by                 VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM_STAGE2B',
    last_batch_id              VARCHAR(64)  NOT NULL,
    previous_revision_json     JSONB        NULL,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_iam_nav_perm_rule_slot UNIQUE (stable_menu_key, permission_scope, rule_group_no, condition_seq)
);

CREATE INDEX IF NOT EXISTS idx_iam_nav_menu_perm_eval_v2
    ON iam.iam_navigation_menu_permission (stable_menu_key, permission_scope, valid, approval_status, mapping_status);

-- -----------------------------------------------------------------------------
-- 4. iam_menu_import_batch：批次审计表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS iam.iam_menu_import_batch (
    batch_id                   VARCHAR(64)  PRIMARY KEY,
    input_manifest_sha256      VARCHAR(64)  NOT NULL,
    normalized_csv_sha256      VARCHAR(64)  NOT NULL,
    mapping_csv_sha256         VARCHAR(64)  NOT NULL,
    plan_payload_sha256        VARCHAR(64)  NOT NULL,
    git_head_sha               VARCHAR(64)  NOT NULL,
    pre_import_catalog_sha256  VARCHAR(64)  NOT NULL DEFAULT '',
    post_import_catalog_sha256 VARCHAR(64)  NOT NULL DEFAULT '',
    is_dry_run                 SMALLINT     NOT NULL DEFAULT 1 CHECK (is_dry_run IN (0, 1)),
    batch_status               VARCHAR(32)  NOT NULL CHECK (batch_status IN ('STAGED', 'APPLIED', 'REPLAYED_NOOP', 'ROLLED_BACK', 'FAILED')),
    inserted_count             INTEGER      NOT NULL DEFAULT 0,
    updated_count              INTEGER      NOT NULL DEFAULT 0,
    unchanged_count            INTEGER      NOT NULL DEFAULT 0,
    operator_id                VARCHAR(64)  NOT NULL,
    correlation_id             VARCHAR(64)  NOT NULL,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- 5. iam_menu_change_audit：变更审计明细表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS iam.iam_menu_change_audit (
    audit_id                   BIGSERIAL    PRIMARY KEY,
    correlation_id             VARCHAR(64)  NOT NULL,
    batch_id                   VARCHAR(64)  NULL REFERENCES iam.iam_menu_import_batch(batch_id),
    actor_id                   VARCHAR(64)  NOT NULL,
    operation_source           VARCHAR(32)  NOT NULL CHECK (operation_source IN ('IMPORT_TOOL', 'ADMIN_UI', 'ROLLBACK_TOOL', 'SYSTEM_MIGRATION')),
    operation_type             VARCHAR(32)  NOT NULL CHECK (operation_type IN ('INSERT_NEW', 'UPDATE_EXISTING', 'LOGICAL_INVALIDATE', 'ROLLBACK_RESTORE', 'ROLLBACK_INVALIDATE', 'APPROVAL_STATE_CHANGE')),
    target_table               VARCHAR(64)  NOT NULL CHECK (target_table IN ('iam_navigation_app', 'iam_navigation_menu', 'iam_navigation_menu_permission')),
    navigation_app_code        VARCHAR(32)  NOT NULL,
    record_key                 VARCHAR(160) NOT NULL,
    before_sha256              VARCHAR(64)  NULL,
    after_sha256               VARCHAR(64)  NOT NULL,
    before_version             INTEGER      NULL,
    after_version              INTEGER      NOT NULL,
    before_snapshot_json       JSONB        NULL,
    after_snapshot_json        JSONB        NOT NULL,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- 6. iam_menu_kvrocks_outbox：事务发件箱表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS iam.iam_menu_kvrocks_outbox (
    outbox_id                  BIGSERIAL    PRIMARY KEY,
    correlation_id             VARCHAR(64)  NOT NULL,
    batch_id                   VARCHAR(64)  NULL,
    navigation_app_code        VARCHAR(32)  NOT NULL,
    cache_key                  VARCHAR(128) NOT NULL,
    catalog_version_hash       VARCHAR(64)  NOT NULL,
    payload_json               JSONB        NOT NULL,
    delivery_status            VARCHAR(32)  NOT NULL DEFAULT 'PENDING' CHECK (delivery_status IN ('PENDING', 'PUBLISHED', 'FAILED_RETRYING', 'SUPERSEDED')),
    retry_count                INTEGER      NOT NULL DEFAULT 0,
    last_error                 VARCHAR(512) NULL,
    next_retry_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at               TIMESTAMPTZ  NULL
);

-- -----------------------------------------------------------------------------
-- 7. 种子数据：8 个纳管系统元数据 + DTS 历史保留系统
-- -----------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------
-- 8. 种子数据：根节点 (APP:IAM, APP:SYS)
-- -----------------------------------------------------------------------------
INSERT INTO iam.iam_navigation_menu (
    stable_menu_key, navigation_app_code, source_app_code, source_menu_id, parent_stable_menu_key,
    hierarchy_code, original_level, normalized_level, sibling_sort_order, menu_name,
    menu_kind, entry_mode, visible, enabled, valid, nav_eligible, source_reference, content_sha256, last_batch_id
) VALUES
('APP:IAM', 'IAM', 'IAM', 'iam', NULL, 'IAM', 1, 1, 1, '统一身份与权限管理系统', 'APP', 'NONE', 1, 1, 1, 0, 'CSV:IAM', 'sha_app_iam', 'BATCH_20261013_B1'),
('APP:SYS', 'SYS', 'SYS', 'sys', NULL, 'SYS', 1, 1, 1, '系统管理系统',         'APP', 'NONE', 1, 1, 1, 0, 'CSV:SYS', 'sha_app_sys', 'BATCH_20261013_B1')
ON CONFLICT (stable_menu_key) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 9. 种子数据：Batch 1 七个静态候选页面 (IAM 4 + SYS 3)
-- -----------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------
-- 10. 种子数据：Batch 1 七个页面权限绑定规则
-- -----------------------------------------------------------------------------
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
