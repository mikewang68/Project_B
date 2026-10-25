-- Unknown historical ownership stays platform-owned. Compatible, repeatable migration.
SET search_path TO sys,public;
BEGIN;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='sys' AND table_name='sys_dict_type' AND column_name='owner_app_code') THEN
  ALTER TABLE sys_dict_type ADD COLUMN owner_app_code VARCHAR(16) NOT NULL DEFAULT 'GLOBAL';
 END IF;
END $$;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='sys' AND table_name='sys_dict_item' AND column_name='owner_app_code') THEN
  ALTER TABLE sys_dict_item ADD COLUMN owner_app_code VARCHAR(16) NOT NULL DEFAULT 'GLOBAL';
 END IF;
END $$;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='sys' AND table_name='sys_config' AND column_name='owner_app_code') THEN
  ALTER TABLE sys_config ADD COLUMN owner_app_code VARCHAR(16) NOT NULL DEFAULT 'GLOBAL';
 END IF;
END $$;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='sys' AND table_name='sys_operation_log' AND column_name='owner_app_code') THEN
  ALTER TABLE sys_operation_log ADD COLUMN owner_app_code VARCHAR(16) NOT NULL DEFAULT 'GLOBAL';
 END IF;
END $$;
CREATE INDEX IF NOT EXISTS ix_sys_dict_owner ON sys_dict_type(owner_app_code);
CREATE INDEX IF NOT EXISTS ix_sys_log_owner_time ON sys_operation_log(owner_app_code,created_at);
COMMIT;
