-- Back up before applying. Historical rows remain UNASSIGNED until their owner
-- explicitly establishes project ownership; this migration never guesses it.
BEGIN;
DO $$
DECLARE existing_pk TEXT;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='ehm'
      AND table_name='aggregate_store' AND column_name='project_id') THEN
    ALTER TABLE ehm.aggregate_store ADD COLUMN project_id VARCHAR(64) NOT NULL DEFAULT 'UNASSIGNED';
    SELECT conname INTO existing_pk FROM pg_constraint WHERE conrelid='ehm.aggregate_store'::regclass AND contype='p';
    IF existing_pk IS NOT NULL THEN
      EXECUTE 'ALTER TABLE ehm.aggregate_store DROP CONSTRAINT '||quote_ident(existing_pk);
    END IF;
    ALTER TABLE ehm.aggregate_store ADD CONSTRAINT pk_ehm_project_aggregate PRIMARY KEY(project_id,aggregate_type,aggregate_id);
  END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_ehm_project_updated ON ehm.aggregate_store(project_id,aggregate_type,updated_at DESC);
COMMIT;
