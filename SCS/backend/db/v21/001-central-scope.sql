-- Apply only to a fingerprinted restore first. Existing organization/area codes
-- do not establish historical project or region ownership.
DO $migration$
DECLARE tab text; col text; definition text;
BEGIN
  FOREACH tab IN ARRAY ARRAY['safety_rule','safety_fence'] LOOP
    FOREACH col IN ARRAY ARRAY['project_id','region_id','subsystem_code'] LOOP
      IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='safety'
          AND table_name=tab AND column_name=col) THEN
        definition:=CASE col WHEN 'project_id' THEN 'varchar(64) NOT NULL DEFAULT ''LEGACY'''
          WHEN 'region_id' THEN 'varchar(64) NOT NULL DEFAULT ''UNASSIGNED'''
          ELSE 'varchar(32) NOT NULL DEFAULT ''SCS''' END;
        EXECUTE format('ALTER TABLE safety.%I ADD COLUMN %I %s',tab,col,definition);
      END IF;
    END LOOP;
    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON safety.%I(project_id,region_id,subsystem_code,id)',
      'ix_'||tab||'_central_scope',tab);
  END LOOP;
END $migration$;
