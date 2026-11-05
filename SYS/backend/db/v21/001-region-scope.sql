-- V2.1 SYS ordered migration; no IAM tables in the SYS database are changed.
ALTER TABLE sys.sys_dict_type ADD COLUMN project_id VARCHAR(64);
ALTER TABLE sys.sys_dict_type ADD COLUMN region_id VARCHAR(64);
ALTER TABLE sys.sys_dict_type ADD COLUMN subsystem_code VARCHAR(32);
ALTER TABLE sys.sys_dict_type ADD COLUMN scope_kind VARCHAR(24) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE sys.sys_dict_type ADD CONSTRAINT ck_v21_sys_dict_type_scope CHECK(scope_kind IN ('LEGACY','GLOBAL','PROJECT','REGION_SYSTEM','PROJECT_REGION_SHARED'));
ALTER TABLE sys.sys_dict_type ADD CONSTRAINT ck_v21_sys_dict_type_identifiers CHECK((project_id IS NULL OR project_id ~ '^[A-Za-z0-9_-]+$') AND (region_id IS NULL OR region_id ~ '^[A-Za-z0-9_-]+$') AND (subsystem_code IS NULL OR subsystem_code ~ '^[A-Za-z0-9_-]+$'));
CREATE INDEX ix_v21_sys_dict_type_scope ON sys.sys_dict_type(project_id,region_id,subsystem_code);
ALTER TABLE sys.sys_dict_item ADD COLUMN project_id VARCHAR(64);
ALTER TABLE sys.sys_dict_item ADD COLUMN region_id VARCHAR(64);
ALTER TABLE sys.sys_dict_item ADD COLUMN subsystem_code VARCHAR(32);
ALTER TABLE sys.sys_dict_item ADD COLUMN scope_kind VARCHAR(24) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE sys.sys_dict_item ADD CONSTRAINT ck_v21_sys_dict_item_scope CHECK(scope_kind IN ('LEGACY','GLOBAL','PROJECT','REGION_SYSTEM','PROJECT_REGION_SHARED'));
ALTER TABLE sys.sys_dict_item ADD CONSTRAINT ck_v21_sys_dict_item_identifiers CHECK((project_id IS NULL OR project_id ~ '^[A-Za-z0-9_-]+$') AND (region_id IS NULL OR region_id ~ '^[A-Za-z0-9_-]+$') AND (subsystem_code IS NULL OR subsystem_code ~ '^[A-Za-z0-9_-]+$'));
CREATE INDEX ix_v21_sys_dict_item_scope ON sys.sys_dict_item(project_id,region_id,subsystem_code);
ALTER TABLE sys.sys_config ADD COLUMN project_id VARCHAR(64);
ALTER TABLE sys.sys_config ADD COLUMN region_id VARCHAR(64);
ALTER TABLE sys.sys_config ADD COLUMN subsystem_code VARCHAR(32);
ALTER TABLE sys.sys_config ADD COLUMN scope_kind VARCHAR(24) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE sys.sys_config ADD CONSTRAINT ck_v21_sys_config_scope CHECK(scope_kind IN ('LEGACY','GLOBAL','PROJECT','REGION_SYSTEM','PROJECT_REGION_SHARED'));
ALTER TABLE sys.sys_config ADD CONSTRAINT ck_v21_sys_config_identifiers CHECK((project_id IS NULL OR project_id ~ '^[A-Za-z0-9_-]+$') AND (region_id IS NULL OR region_id ~ '^[A-Za-z0-9_-]+$') AND (subsystem_code IS NULL OR subsystem_code ~ '^[A-Za-z0-9_-]+$'));
CREATE INDEX ix_v21_sys_config_scope ON sys.sys_config(project_id,region_id,subsystem_code);
ALTER TABLE sys.sys_operation_log ADD COLUMN project_id VARCHAR(64);
ALTER TABLE sys.sys_operation_log ADD COLUMN region_id VARCHAR(64);
ALTER TABLE sys.sys_operation_log ADD COLUMN subsystem_code VARCHAR(32);
ALTER TABLE sys.sys_operation_log ADD COLUMN scope_kind VARCHAR(24) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE sys.sys_operation_log ADD CONSTRAINT ck_v21_sys_operation_log_scope CHECK(scope_kind IN ('LEGACY','GLOBAL','PROJECT','REGION_SYSTEM','PROJECT_REGION_SHARED'));
ALTER TABLE sys.sys_operation_log ADD CONSTRAINT ck_v21_sys_operation_log_identifiers CHECK((project_id IS NULL OR project_id ~ '^[A-Za-z0-9_-]+$') AND (region_id IS NULL OR region_id ~ '^[A-Za-z0-9_-]+$') AND (subsystem_code IS NULL OR subsystem_code ~ '^[A-Za-z0-9_-]+$'));
CREATE INDEX ix_v21_sys_operation_log_scope ON sys.sys_operation_log(project_id,region_id,subsystem_code);
ALTER TABLE sys.sys_audit_outbox ADD COLUMN project_id VARCHAR(64);
ALTER TABLE sys.sys_audit_outbox ADD COLUMN region_id VARCHAR(64);
ALTER TABLE sys.sys_audit_outbox ADD COLUMN subsystem_code VARCHAR(32);
ALTER TABLE sys.sys_audit_outbox ADD COLUMN scope_kind VARCHAR(24) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE sys.sys_audit_outbox ADD CONSTRAINT ck_v21_sys_audit_outbox_scope CHECK(scope_kind IN ('LEGACY','GLOBAL','PROJECT','REGION_SYSTEM','PROJECT_REGION_SHARED'));
ALTER TABLE sys.sys_audit_outbox ADD CONSTRAINT ck_v21_sys_audit_outbox_identifiers CHECK((project_id IS NULL OR project_id ~ '^[A-Za-z0-9_-]+$') AND (region_id IS NULL OR region_id ~ '^[A-Za-z0-9_-]+$') AND (subsystem_code IS NULL OR subsystem_code ~ '^[A-Za-z0-9_-]+$'));
CREATE INDEX ix_v21_sys_audit_outbox_scope ON sys.sys_audit_outbox(project_id,region_id,subsystem_code);
-- Cross-database FKs are not claimed: SYS validates against trusted IAM context and approved local code catalog.
-- No UPDATE/backfill, DROP, privilege expansion, fixed-region DEFAULT, user/password seed or execution in this stage.

ALTER TABLE sys.sys_dict_type ADD CONSTRAINT ck_v21_sys_dict_type_global_only CHECK(scope_kind='LEGACY' OR (scope_kind='GLOBAL' AND project_id IS NULL AND region_id IS NULL AND subsystem_code IS NULL));
ALTER TABLE sys.sys_dict_item ADD CONSTRAINT ck_v21_sys_dict_item_global_only CHECK(scope_kind='LEGACY' OR (scope_kind='GLOBAL' AND project_id IS NULL AND region_id IS NULL AND subsystem_code IS NULL));
ALTER TABLE sys.sys_config ADD CONSTRAINT ck_v21_sys_config_global_only CHECK(scope_kind='LEGACY' OR (scope_kind='GLOBAL' AND project_id IS NULL AND region_id IS NULL AND subsystem_code IS NULL));
ALTER TABLE sys.sys_operation_log ADD CONSTRAINT ck_v21_sys_operation_log_scope_shape CHECK(scope_kind='LEGACY' OR (scope_kind='GLOBAL' AND project_id IS NULL AND region_id IS NULL AND subsystem_code IS NULL) OR (scope_kind='PROJECT' AND project_id IS NOT NULL AND region_id IS NULL) OR (scope_kind IN ('REGION_SYSTEM','PROJECT_REGION_SHARED') AND project_id IS NOT NULL AND region_id IS NOT NULL AND subsystem_code IS NOT NULL));
ALTER TABLE sys.sys_audit_outbox ADD CONSTRAINT ck_v21_sys_audit_outbox_scope_shape CHECK(scope_kind='LEGACY' OR (scope_kind='GLOBAL' AND project_id IS NULL AND region_id IS NULL AND subsystem_code IS NULL) OR (scope_kind='PROJECT' AND project_id IS NOT NULL AND region_id IS NULL) OR (scope_kind IN ('REGION_SYSTEM','PROJECT_REGION_SHARED') AND project_id IS NOT NULL AND region_id IS NOT NULL AND subsystem_code IS NOT NULL));
