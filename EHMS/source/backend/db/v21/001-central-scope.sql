-- In this session, explicitly set ehm.migration.schema and search_path to the audited EHMS schema.
-- No historical region assignment is inferred from organization/zone or device IDs.
BEGIN;
DO $migration$
DECLARE sch text:=current_setting('ehm.migration.schema'); pk text; definition text;
BEGIN
  IF sch IS NULL OR sch !~ '^[A-Za-z_][A-Za-z0-9_]{0,62}$'
      OR sch IN ('public','pg_catalog','information_schema')
      OR sch IS DISTINCT FROM current_schema() THEN
    RAISE EXCEPTION 'Explicit approved EHMS schema must match search_path';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM pg_class t JOIN pg_namespace n ON n.oid=t.relnamespace
      WHERE n.nspname=sch AND t.relname='aggregate_store' AND t.relkind='r') THEN
    RAISE EXCEPTION 'Expected EHMS aggregate table is absent';
  END IF;
  EXECUTE format('LOCK TABLE %I.aggregate_store IN ACCESS EXCLUSIVE MODE NOWAIT',sch);
  SELECT c.conname,regexp_replace(lower(pg_get_constraintdef(c.oid,true)),'[[:space:]"]','','g') INTO pk,definition
    FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE n.nspname=sch AND t.relname='aggregate_store' AND c.contype='p';
  IF definition IS NULL OR definition NOT IN (
      'primarykey(project_id,aggregate_type,aggregate_id)',
      'primarykey(project_id,region_id,subsystem_code,aggregate_type,aggregate_id)') THEN
    RAISE EXCEPTION 'Unexpected aggregate primary key; inspect before migration';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=sch AND table_name='aggregate_store'
      AND column_name='region_id' AND (data_type<>'character varying'
        OR character_maximum_length IS DISTINCT FROM 64 OR is_nullable<>'NO'))
      OR EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=sch AND table_name='aggregate_store'
      AND column_name='subsystem_code' AND (data_type<>'character varying'
        OR character_maximum_length IS NULL OR character_maximum_length NOT IN (32,64) OR is_nullable<>'NO')) THEN
    RAISE EXCEPTION 'Unexpected existing region/subsystem column definition';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=sch AND table_name='aggregate_store' AND column_name='region_id') THEN
    EXECUTE format('ALTER TABLE %I.aggregate_store ADD COLUMN region_id varchar(64) NOT NULL DEFAULT ''UNASSIGNED''',sch);
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=sch AND table_name='aggregate_store' AND column_name='subsystem_code') THEN
    EXECUTE format('ALTER TABLE %I.aggregate_store ADD COLUMN subsystem_code varchar(32) NOT NULL DEFAULT ''EHMS''',sch);
  END IF;
  IF definition='primarykey(project_id,aggregate_type,aggregate_id)' THEN
    EXECUTE format('ALTER TABLE %I.aggregate_store DROP CONSTRAINT %I',sch,pk);
    EXECUTE format('ALTER TABLE %I.aggregate_store ADD PRIMARY KEY(project_id,region_id,subsystem_code,aggregate_type,aggregate_id)',sch);
  END IF;
  EXECUTE format('CREATE INDEX IF NOT EXISTS ix_ehm_aggregate_central ON %I.aggregate_store(project_id,region_id,subsystem_code,aggregate_type,updated_at DESC)',sch);
END $migration$;
COMMIT;
