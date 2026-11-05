-- SCS regional resources. Run after 001-central-scope.sql against the
-- explicitly selected application database, with a migration owner account.
-- Existing rows remain unassigned: no project/region is inferred or backfilled.
-- PostgreSQL-compatible openGauss schema migration; stop on any SQL error.
BEGIN;

-- Fail immediately if an existing workload prevents this short DDL operation.
LOCK TABLE safety.safety_alert, safety.safety_personnel, safety.collision_device,
  safety.ai_event, safety.edge_node, safety.edge_pending_event,
  safety.ops_event_log, safety.device_camera, safety.safety_area
  IN ACCESS EXCLUSIVE MODE NOWAIT;

DO $migration$
DECLARE
  tab text;
  col text;
  width integer;
  existing record;
BEGIN
  FOREACH tab IN ARRAY ARRAY[
    'safety_alert', 'safety_personnel', 'collision_device', 'ai_event',
    'edge_node', 'edge_pending_event', 'ops_event_log', 'device_camera', 'safety_area'
  ] LOOP
    FOREACH col IN ARRAY ARRAY['project_id', 'region_id', 'subsystem_code'] LOOP
      width := CASE WHEN col = 'subsystem_code' THEN 32 ELSE 64 END;
      SELECT data_type, character_maximum_length, is_nullable, column_default
        INTO existing
        FROM information_schema.columns
        WHERE table_schema = 'safety' AND table_name = tab AND column_name = col;
      IF NOT FOUND THEN
        EXECUTE format('ALTER TABLE safety.%I ADD COLUMN %I varchar(%s)', tab, col, width);
      ELSIF existing.data_type <> 'character varying'
        OR existing.character_maximum_length IS DISTINCT FROM width
        OR existing.is_nullable <> 'YES'
        OR existing.column_default IS NOT NULL THEN
        RAISE EXCEPTION 'Unexpected existing definition for safety.%.%; inspect before migration', tab, col;
      END IF;
    END LOOP;
  END LOOP;
END $migration$;

-- Runtime objects such as an approved UWB identifier need an explicit owner.
-- This table is empty after creation. Its rows are deployment-specific facts,
-- supplied by the resource owner; this migration grants no device authority.
CREATE TABLE IF NOT EXISTS safety.scope_runtime_ownership (
  resource_key varchar(128) PRIMARY KEY,
  project_id varchar(64) NOT NULL CHECK (project_id ~ '^[A-Za-z0-9_-]+$'),
  region_id varchar(64) NOT NULL CHECK (region_id ~ '^[A-Za-z0-9_-]+$'),
  subsystem_code varchar(32) NOT NULL CHECK (subsystem_code = 'SCS'),
  source_basis varchar(240) NOT NULL,
  created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- A repeated run must not silently accept a different runtime ownership table.
DO $migration$
DECLARE
  expected record;
  existing record;
BEGIN
  FOR expected IN
    SELECT * FROM (VALUES
      ('resource_key', 'character varying', 128),
      ('project_id', 'character varying', 64),
      ('region_id', 'character varying', 64),
      ('subsystem_code', 'character varying', 32),
      ('source_basis', 'character varying', 240),
      ('created_at', 'timestamp with time zone', NULL::integer)
    ) AS definitions(column_name, data_type, max_length)
  LOOP
    SELECT data_type, character_maximum_length, is_nullable
      INTO existing FROM information_schema.columns
      WHERE table_schema = 'safety' AND table_name = 'scope_runtime_ownership'
        AND column_name = expected.column_name;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'Missing safety.scope_runtime_ownership.%', expected.column_name;
    END IF;
    IF existing.data_type <> expected.data_type
      OR existing.character_maximum_length IS DISTINCT FROM expected.max_length
      OR existing.is_nullable <> 'NO' THEN
      RAISE EXCEPTION 'Unexpected safety.scope_runtime_ownership.% definition', expected.column_name;
    END IF;
  END LOOP;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kc
      ON kc.constraint_catalog = tc.constraint_catalog
      AND kc.constraint_schema = tc.constraint_schema AND kc.constraint_name = tc.constraint_name
    WHERE tc.table_schema = 'safety' AND tc.table_name = 'scope_runtime_ownership'
      AND tc.constraint_type = 'PRIMARY KEY'
    GROUP BY tc.constraint_name
    HAVING count(*) = 1 AND min(kc.column_name) = 'resource_key'
  ) THEN
    RAISE EXCEPTION 'Runtime ownership requires a single-column resource_key primary key';
  END IF;
END $migration$;

COMMIT;
