-- Add fields already required by the canonical schema and current repositories.
-- Compatible with restored older installations; no tables or history are replaced.
BEGIN;
DO $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='safety' AND table_name='sys_team' AND column_name='is_deleted') THEN
  ALTER TABLE safety.sys_team ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT false;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='safety' AND table_name='safety_area' AND column_name='is_deleted') THEN
  ALTER TABLE safety.safety_area ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT false;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='safety' AND table_name='safety_fence' AND column_name='buffer_distance') THEN
  ALTER TABLE safety.safety_fence ADD COLUMN buffer_distance NUMERIC(6,2) NOT NULL DEFAULT 5.00;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='safety' AND table_name='safety_fence_version' AND column_name='buffer_distance') THEN
  ALTER TABLE safety.safety_fence_version ADD COLUMN buffer_distance NUMERIC(6,2) NOT NULL DEFAULT 5.00;
 END IF;
END $$;
COMMIT;
