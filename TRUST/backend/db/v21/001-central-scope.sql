-- Existing organization ownership remains authoritative. No org-to-region inference.
-- Views and retention triggers keep their existing columns; regional reads use
-- correlated ownership predicates against these immutable root records.
DO $migration$
DECLARE tab text; col text; definition text;
BEGIN
  FOREACH tab IN ARRAY ARRAY['events','evidence'] LOOP
    FOREACH col IN ARRAY ARRAY['project_id','region_id','subsystem_code'] LOOP
      IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='trust_data' AND table_name=tab AND column_name=col) THEN
        definition:=CASE col WHEN 'project_id' THEN 'varchar(64) NOT NULL DEFAULT ''LEGACY'''
          WHEN 'region_id' THEN 'varchar(64) NOT NULL DEFAULT ''UNASSIGNED'''
          ELSE 'varchar(32) NOT NULL DEFAULT ''TRUST''' END;
        EXECUTE format('ALTER TABLE trust_data.%I ADD COLUMN %I %s',tab,col,definition);
      END IF;
    END LOOP;
    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON trust_data.%I(project_id,region_id,subsystem_code,id)', 'ix_'||tab||'_central_scope',tab);
  END LOOP;
END $migration$;
