-- Warehouse is the regional root; transaction rows inherit its tuple through warehouse_id.
-- Company, warehouse-user and owner restrictions remain mandatory.
DO $migration$
DECLARE col text; definition text;
BEGIN
  FOREACH col IN ARRAY ARRAY['project_id','region_id','subsystem_code']
  LOOP
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
        WHERE table_schema=current_schema() AND table_name='wms_warehouse' AND column_name=col) THEN
      definition := CASE col
        WHEN 'project_id' THEN 'varchar(64) NOT NULL DEFAULT ''LEGACY'''
        WHEN 'region_id' THEN 'varchar(64) NOT NULL DEFAULT ''UNASSIGNED'''
        ELSE 'varchar(32) NOT NULL DEFAULT ''WMS''' END;
      EXECUTE format('ALTER TABLE wms_warehouse ADD COLUMN %I %s',col,definition);
    END IF;
  END LOOP;
END $migration$;
CREATE INDEX IF NOT EXISTS ix_wms_warehouse_central_scope
  ON wms_warehouse(project_id,region_id,subsystem_code,company_id,status,id);
DO $migration$
BEGIN
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
      AND table_name='async_task' AND column_name='scope_context_version') THEN
    ALTER TABLE async_task ADD COLUMN scope_context_version integer NOT NULL DEFAULT 1;
    ALTER TABLE async_task ADD COLUMN iam_subject varchar(64);
    ALTER TABLE async_task ADD COLUMN iam_session_id varchar(256);
    ALTER TABLE async_task ADD CONSTRAINT ck_async_task_context_reference CHECK
      (scope_context_version=1 OR scope_context_version=2 AND iam_subject IS NOT NULL AND iam_session_id IS NOT NULL);
  END IF;
END $migration$;
