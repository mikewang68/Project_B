-- V2.1 ordered migration; transaction, preflight and ledger are supplied by the runner.
ALTER TABLE iam.iam_user ADD COLUMN user_code VARCHAR(64);
ALTER TABLE iam.iam_user ADD COLUMN admin_level VARCHAR(32);
ALTER TABLE iam.iam_user ADD COLUMN read_only BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE iam.iam_user ADD COLUMN scope_state VARCHAR(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE iam.iam_user ADD COLUMN project_id VARCHAR(64);
ALTER TABLE iam.iam_user ADD COLUMN region_id VARCHAR(64);
ALTER TABLE iam.iam_user ADD COLUMN subsystem_code VARCHAR(32);
ALTER TABLE iam.iam_user ADD COLUMN inherited_from_user VARCHAR(32);
ALTER TABLE iam.iam_user ADD COLUMN authz_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE iam.iam_role ADD COLUMN role_scope VARCHAR(24) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE iam.iam_role ADD COLUMN project_id VARCHAR(64);
ALTER TABLE iam.iam_role ADD COLUMN region_id VARCHAR(64);
ALTER TABLE iam.iam_role ADD COLUMN subsystem_code VARCHAR(32);
ALTER TABLE iam.iam_role ADD COLUMN business_role_code VARCHAR(64);
ALTER TABLE iam.iam_role ADD COLUMN authz_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE iam.iam_navigation_app ADD COLUMN context_contract_max INTEGER NOT NULL DEFAULT 1;
ALTER TABLE iam.iam_navigation_menu ADD CONSTRAINT uq_v21_nav_app_key UNIQUE(navigation_app_code,stable_menu_key);
CREATE UNIQUE INDEX uk_v21_username_normalized ON iam.iam_user(lower(trim(username))) WHERE deleted=0;
CREATE UNIQUE INDEX uk_v21_user_code ON iam.iam_user(user_code) WHERE user_code IS NOT NULL;
CREATE UNIQUE INDEX uk_v21_scoped_business_role ON iam.iam_role(project_id,region_id,subsystem_code,business_role_code) WHERE deleted=0 AND role_scope='REGION_SYSTEM';

-- Reviewed V2.1 schema expansion.
-- Existing users/roles/navigation tables are retained. DDL uses openGauss 6.0.5 types.
CREATE TABLE iam.iam_project_preset (
 project_id VARCHAR(64) PRIMARY KEY CHECK(project_id ~ '^[A-Za-z0-9_-]+$'),
 project_name VARCHAR(128) NOT NULL, company_name VARCHAR(128),
 status VARCHAR(16) NOT NULL DEFAULT 'disabled' CHECK(status IN ('active','disabled')),
 authz_version BIGINT NOT NULL DEFAULT 0 CHECK(authz_version>=0),
 created_by VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE iam.iam_region_preset (
 project_id VARCHAR(64) NOT NULL REFERENCES iam.iam_project_preset(project_id),
 region_id VARCHAR(64) NOT NULL CHECK(region_id ~ '^[A-Za-z0-9_-]+$'),
 region_name VARCHAR(128) NOT NULL, region_type VARCHAR(32) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'disabled' CHECK(status IN ('active','disabled')),
 created_by VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(project_id,region_id)
);
CREATE TABLE iam.iam_operation_perm_dict (
 op_code VARCHAR(64) PRIMARY KEY CHECK(op_code ~ '^[A-Z][A-Z0-9_]*$'),
 op_name VARCHAR(64) NOT NULL, op_category VARCHAR(32) NOT NULL,
 effect_kind VARCHAR(16) NOT NULL CHECK(effect_kind IN ('READ','WRITE','EXPORT')),
 allow_readonly BOOLEAN NOT NULL DEFAULT FALSE,
 status VARCHAR(16) NOT NULL DEFAULT 'active' CHECK(status IN ('active','disabled')),
 created_by VARCHAR(64) NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK(NOT allow_readonly OR effect_kind IN ('READ','EXPORT'))
);
CREATE TABLE iam.iam_menu_operation (
 stable_menu_key VARCHAR(128) NOT NULL REFERENCES iam.iam_navigation_menu(stable_menu_key),
 op_code VARCHAR(64) NOT NULL REFERENCES iam.iam_operation_perm_dict(op_code),
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(stable_menu_key,op_code)
);
-- A legacy permission may guard multiple actions. It is an adapter, never the sole V2 action grant.
CREATE TABLE iam.iam_operation_permission_map (
 stable_menu_key VARCHAR(128) NOT NULL, op_code VARCHAR(64) NOT NULL,
 permission_id VARCHAR(128) NOT NULL REFERENCES iam.iam_permission(id),
 mapping_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(mapping_status IN ('PENDING','VERIFIED','DISABLED')),
 PRIMARY KEY(stable_menu_key,op_code,permission_id),
 FOREIGN KEY(stable_menu_key,op_code) REFERENCES iam.iam_menu_operation(stable_menu_key,op_code)
);
CREATE TABLE iam.iam_project_system_grant (
 project_id VARCHAR(64) NOT NULL, region_id VARCHAR(64) NOT NULL,
 subsystem_code VARCHAR(32) NOT NULL REFERENCES iam.iam_navigation_app(app_code),
 l1_allowed BOOLEAN NOT NULL DEFAULT FALSE, l2_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 updated_by VARCHAR(64) NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(project_id,region_id,subsystem_code),
 FOREIGN KEY(project_id,region_id) REFERENCES iam.iam_region_preset(project_id,region_id)
);
CREATE TABLE iam.iam_project_menu_grant (
 project_id VARCHAR(64) NOT NULL, region_id VARCHAR(64) NOT NULL, subsystem_code VARCHAR(32) NOT NULL,
 stable_menu_key VARCHAR(128) NOT NULL, op_code VARCHAR(64) NOT NULL,
 l1_allowed BOOLEAN NOT NULL DEFAULT FALSE, l2_checked BOOLEAN NOT NULL DEFAULT FALSE,
 updated_by VARCHAR(64) NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(project_id,region_id,subsystem_code,stable_menu_key,op_code),
 FOREIGN KEY(project_id,region_id,subsystem_code) REFERENCES iam.iam_project_system_grant(project_id,region_id,subsystem_code),
 FOREIGN KEY(stable_menu_key,op_code) REFERENCES iam.iam_menu_operation(stable_menu_key,op_code),
 FOREIGN KEY(subsystem_code,stable_menu_key) REFERENCES iam.iam_navigation_menu(navigation_app_code,stable_menu_key)
);
CREATE TABLE iam.iam_role_operation_grant (
 role_id VARCHAR(32) NOT NULL REFERENCES iam.iam_role(id),
 stable_menu_key VARCHAR(128) NOT NULL, op_code VARCHAR(64) NOT NULL,
 created_by VARCHAR(64) NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(role_id,stable_menu_key,op_code),
 FOREIGN KEY(stable_menu_key,op_code) REFERENCES iam.iam_menu_operation(stable_menu_key,op_code)
);
CREATE TABLE iam.iam_scope_resource (
 resource_key VARCHAR(128) PRIMARY KEY,
 owner_app_code VARCHAR(32) NOT NULL REFERENCES iam.iam_navigation_app(app_code),
 scope_kind VARCHAR(32) NOT NULL CHECK(scope_kind IN ('GLOBAL','PROJECT','REGION_SYSTEM','PROJECT_REGION_SHARED')),
 database_key VARCHAR(64) NOT NULL,
 table_schema VARCHAR(64) NOT NULL, table_name VARCHAR(64) NOT NULL,
 alias_profile VARCHAR(64) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 CHECK(table_schema ~ '^[A-Za-z_][A-Za-z0-9_]*$'),
 CHECK(table_name ~ '^[A-Za-z_][A-Za-z0-9_]*$'),
 UNIQUE(database_key,table_schema,table_name)
);
CREATE TABLE iam.iam_shared_resource_grant (
 resource_key VARCHAR(128) NOT NULL REFERENCES iam.iam_scope_resource(resource_key),
 project_id VARCHAR(64) NOT NULL, region_id VARCHAR(64) NOT NULL,
 consumer_app_code VARCHAR(32) NOT NULL, op_code VARCHAR(64) NOT NULL REFERENCES iam.iam_operation_perm_dict(op_code),
 enabled BOOLEAN NOT NULL DEFAULT FALSE, updated_by VARCHAR(64) NOT NULL,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(resource_key,project_id,region_id,consumer_app_code,op_code),
 FOREIGN KEY(project_id,region_id,consumer_app_code) REFERENCES iam.iam_project_system_grant(project_id,region_id,subsystem_code)
);
CREATE TABLE iam.iam_scope_alias_map (
 source_system VARCHAR(32) NOT NULL, source_field VARCHAR(64) NOT NULL,
 source_value VARCHAR(128) NOT NULL, project_id VARCHAR(64) NOT NULL, region_id VARCHAR(64) NOT NULL,
 approved_by VARCHAR(64) NOT NULL, evidence_ref VARCHAR(255) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(source_system,source_field,source_value,project_id),
 FOREIGN KEY(project_id,region_id) REFERENCES iam.iam_region_preset(project_id,region_id)
);
CREATE INDEX ix_v21_region_status ON iam.iam_region_preset(project_id,status);
CREATE INDEX ix_v21_project_menu_scope ON iam.iam_project_menu_grant(project_id,region_id,subsystem_code);
CREATE INDEX ix_v21_operation_legacy ON iam.iam_operation_permission_map(permission_id,mapping_status);
CREATE INDEX ix_v21_shared_consumer ON iam.iam_shared_resource_grant(project_id,region_id,consumer_app_code);

ALTER TABLE iam.iam_user ADD CONSTRAINT ck_v21_scope_state CHECK(scope_state IN ('PENDING','V2_READY'));
ALTER TABLE iam.iam_user ADD CONSTRAINT ck_v21_admin_level CHECK(admin_level IS NULL OR admin_level IN ('L1_SUPER','L1_READONLY','L2_PROJECT','L3_SUBSYSTEM','L4_OPERATOR'));
ALTER TABLE iam.iam_user ADD CONSTRAINT ck_v21_ready_scope CHECK(scope_state<>'V2_READY' OR (admin_level IS NOT NULL AND ((admin_level IN ('L1_SUPER','L1_READONLY') AND project_id IS NULL AND region_id IS NULL AND subsystem_code IS NULL) OR (admin_level='L2_PROJECT' AND project_id IS NOT NULL AND region_id IS NULL AND subsystem_code IS NULL) OR (admin_level IN ('L3_SUBSYSTEM','L4_OPERATOR') AND project_id IS NOT NULL AND region_id IS NOT NULL AND subsystem_code IS NOT NULL))));
ALTER TABLE iam.iam_user ADD CONSTRAINT ck_v21_readonly_level CHECK(admin_level IS NULL OR admin_level<>'L1_READONLY' OR read_only=TRUE);
ALTER TABLE iam.iam_user ADD CONSTRAINT fk_v21_user_project FOREIGN KEY(project_id) REFERENCES iam.iam_project_preset(project_id);
ALTER TABLE iam.iam_user ADD CONSTRAINT fk_v21_user_region FOREIGN KEY(project_id,region_id) REFERENCES iam.iam_region_preset(project_id,region_id);
ALTER TABLE iam.iam_user ADD CONSTRAINT fk_v21_user_app FOREIGN KEY(subsystem_code) REFERENCES iam.iam_navigation_app(app_code);
ALTER TABLE iam.iam_user ADD CONSTRAINT fk_v21_inherited_user FOREIGN KEY(inherited_from_user) REFERENCES iam.iam_user(id);
ALTER TABLE iam.iam_role ADD CONSTRAINT ck_v21_role_scope CHECK(role_scope IN ('LEGACY','GLOBAL','PROJECT','REGION_SYSTEM'));
ALTER TABLE iam.iam_role ADD CONSTRAINT ck_v21_role_ownership CHECK(role_scope='LEGACY' OR (role_scope='GLOBAL' AND project_id IS NULL AND region_id IS NULL AND subsystem_code IS NULL) OR (role_scope='PROJECT' AND project_id IS NOT NULL AND region_id IS NULL AND subsystem_code IS NULL) OR (role_scope='REGION_SYSTEM' AND project_id IS NOT NULL AND region_id IS NOT NULL AND subsystem_code IS NOT NULL AND business_role_code IS NOT NULL));
ALTER TABLE iam.iam_role ADD CONSTRAINT fk_v21_role_project FOREIGN KEY(project_id) REFERENCES iam.iam_project_preset(project_id);
ALTER TABLE iam.iam_role ADD CONSTRAINT fk_v21_role_region FOREIGN KEY(project_id,region_id) REFERENCES iam.iam_region_preset(project_id,region_id);
ALTER TABLE iam.iam_role ADD CONSTRAINT fk_v21_role_app FOREIGN KEY(subsystem_code) REFERENCES iam.iam_navigation_app(app_code);
ALTER TABLE iam.iam_user ADD CONSTRAINT ck_v21_user_scope_identifiers CHECK((project_id IS NULL OR project_id ~ '^[A-Za-z0-9_-]+$') AND (region_id IS NULL OR region_id ~ '^[A-Za-z0-9_-]+$') AND (subsystem_code IS NULL OR subsystem_code ~ '^[A-Za-z0-9_-]+$'));
ALTER TABLE iam.iam_user ADD CONSTRAINT ck_v21_user_authz_version CHECK(authz_version>=0);
CREATE INDEX ix_v21_user_scope ON iam.iam_user(project_id,region_id,subsystem_code,scope_state);
CREATE INDEX ix_v21_role_scope ON iam.iam_role(project_id,region_id,subsystem_code,role_scope);
ALTER TABLE iam.iam_user ADD CONSTRAINT ck_v21_l4_inheritance CHECK(scope_state<>'V2_READY' OR admin_level<>'L4_OPERATOR' OR inherited_from_user IS NOT NULL);
-- Registry expansion: replace fixed eight-system constraints; preserve legacy GLOBAL ownership.
ALTER TABLE iam.iam_role_app_scope ALTER COLUMN app_code TYPE VARCHAR(32);
ALTER TABLE iam.iam_role_app_scope DROP CONSTRAINT "iam_role_app_scope_app_code_check";
ALTER TABLE iam.iam_management_owner ALTER COLUMN app_code TYPE VARCHAR(32);
ALTER TABLE iam.iam_management_owner DROP CONSTRAINT "iam_management_owner_app_code_check";
ALTER TABLE iam.iam_role_app_scope ADD CONSTRAINT fk_v21_role_app_catalog FOREIGN KEY(app_code) REFERENCES iam.iam_navigation_app(app_code);
ALTER TABLE iam.iam_management_owner ADD CONSTRAINT ck_v21_owner_app_syntax CHECK(app_code='GLOBAL' OR app_code ~ '^[A-Za-z0-9_-]+$');
ALTER TABLE iam.sso_project_grant ALTER COLUMN audience TYPE VARCHAR(32);
CREATE OR REPLACE FUNCTION iam.v21_check_management_app() RETURNS trigger AS $$ BEGIN IF NEW.app_code <> 'GLOBAL' AND NOT EXISTS (SELECT 1 FROM iam.iam_navigation_app a WHERE a.app_code=NEW.app_code) THEN RAISE EXCEPTION 'management app not registered'; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_v21_management_app BEFORE INSERT OR UPDATE ON iam.iam_management_owner FOR EACH ROW EXECUTE PROCEDURE iam.v21_check_management_app();
-- Catalog uses soft disable; physical deletion of referenced applications is not exposed.

UPDATE iam.iam_user SET user_code=id WHERE user_code IS NULL;
-- V2.1 DESIGN ONLY. NOT EXECUTED. These 12 actions do not grant any role permissions.
INSERT INTO iam.iam_operation_perm_dict(op_code,op_name,op_category,effect_kind,allow_readonly,status,created_by) VALUES('VIEW','查询/查看','BASIC','READ',TRUE,'active','SYSTEM_V21');
INSERT INTO iam.iam_operation_perm_dict(op_code,op_name,op_category,effect_kind,allow_readonly,status,created_by) VALUES('ADD','新增','BASIC','WRITE',FALSE,'active','SYSTEM_V21');
INSERT INTO iam.iam_operation_perm_dict(op_code,op_name,op_category,effect_kind,allow_readonly,status,created_by) VALUES('EDIT','修改','BASIC','WRITE',FALSE,'active','SYSTEM_V21');
INSERT INTO iam.iam_operation_perm_dict(op_code,op_name,op_category,effect_kind,allow_readonly,status,created_by) VALUES('DELETE','删除','BASIC','WRITE',FALSE,'active','SYSTEM_V21');
INSERT INTO iam.iam_operation_perm_dict(op_code,op_name,op_category,effect_kind,allow_readonly,status,created_by) VALUES('UPLOAD','上传/导入','FILE','WRITE',FALSE,'active','SYSTEM_V21');
INSERT INTO iam.iam_operation_perm_dict(op_code,op_name,op_category,effect_kind,allow_readonly,status,created_by) VALUES('DOWNLOAD','下载已有文件','FILE','EXPORT',TRUE,'active','SYSTEM_V21');
INSERT INTO iam.iam_operation_perm_dict(op_code,op_name,op_category,effect_kind,allow_readonly,status,created_by) VALUES('FAULT_ALARM','报警上报及处置','DEVICE','WRITE',FALSE,'active','SYSTEM_V21');
INSERT INTO iam.iam_operation_perm_dict(op_code,op_name,op_category,effect_kind,allow_readonly,status,created_by) VALUES('RECEIVE_EMAIL','触发收件解析','AUTOMATION','WRITE',FALSE,'active','SYSTEM_V21');
INSERT INTO iam.iam_operation_perm_dict(op_code,op_name,op_category,effect_kind,allow_readonly,status,created_by) VALUES('EXPORT','生成数据导出','FILE','EXPORT',TRUE,'active','SYSTEM_V21');
INSERT INTO iam.iam_operation_perm_dict(op_code,op_name,op_category,effect_kind,allow_readonly,status,created_by) VALUES('CONFIRM','确认/锁库','BUSINESS','WRITE',FALSE,'active','SYSTEM_V21');
INSERT INTO iam.iam_operation_perm_dict(op_code,op_name,op_category,effect_kind,allow_readonly,status,created_by) VALUES('AUDIT_VERIFY','链上只读核验','TRUST','READ',TRUE,'active','SYSTEM_V21');
INSERT INTO iam.iam_operation_perm_dict(op_code,op_name,op_category,effect_kind,allow_readonly,status,created_by) VALUES('CHAIN_SUBMIT','提交/重试上链','TRUST','WRITE',FALSE,'active','SYSTEM_V21');
