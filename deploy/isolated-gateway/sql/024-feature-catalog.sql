BEGIN;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='iam'
      AND table_name='iam_navigation_menu' AND column_name='feature_status') THEN
    ALTER TABLE iam.iam_navigation_menu ADD COLUMN feature_status VARCHAR(32) NOT NULL DEFAULT 'READY';
    ALTER TABLE iam.iam_navigation_menu ADD COLUMN unavailable_reason VARCHAR(255) NOT NULL DEFAULT '';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='iam'
      AND table_name='iam_navigation_app' AND column_name='sso_enabled') THEN
    ALTER TABLE iam.iam_navigation_app ADD COLUMN sso_enabled BOOLEAN NOT NULL DEFAULT false;
  END IF;
END $$;
COMMENT ON COLUMN iam.iam_navigation_menu.feature_status IS 'READY/PARTIAL/PENDING_VERIFICATION/PLANNED; does not grant launch authorization';
COMMENT ON COLUMN iam.iam_navigation_app.sso_enabled IS 'Enable only after the target browser and native session bridge is deployed';
COMMIT;
