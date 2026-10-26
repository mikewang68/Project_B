-- Compatible address publication metadata. Apply with a migration account, before the new backend.
BEGIN;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='iam'
      AND table_name='iam_navigation_app' AND column_name='address_version') THEN
    ALTER TABLE iam.iam_navigation_app ADD COLUMN address_version BIGINT NOT NULL DEFAULT 1;
  END IF;
END $$;
CREATE TABLE IF NOT EXISTS iam.iam_navigation_address_revision (
  revision_id VARCHAR(36) PRIMARY KEY,
  app_code VARCHAR(16) NOT NULL,
  base_version BIGINT NOT NULL,
  published_version BIGINT,
  address_json TEXT NOT NULL,
  state VARCHAR(16) NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  validated_at TIMESTAMP,
  validation_message VARCHAR(500),
  published_at TIMESTAMP,
  retire_after TIMESTAMP,
  UNIQUE(app_code,published_version)
);
CREATE INDEX IF NOT EXISTS idx_iam_address_revision_history
  ON iam.iam_navigation_address_revision(app_code,created_at);
COMMIT;
