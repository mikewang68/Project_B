-- Run before starting an IAM build that reads the address columns.
-- Environment-specific domains/addresses belong in private runtime configuration.
BEGIN;
DO $$
DECLARE
  col RECORD;
BEGIN
  FOR col IN SELECT * FROM (VALUES
    ('domain_name', 'VARCHAR(253) NOT NULL DEFAULT '''''),
    ('public_scheme', 'VARCHAR(5) NOT NULL DEFAULT ''https'''),
    ('public_port', 'INTEGER'),
    ('origin_scheme', 'VARCHAR(5) NOT NULL DEFAULT ''http'''),
    ('origin_ip', 'VARCHAR(45) NOT NULL DEFAULT '''''),
    ('origin_port', 'INTEGER'),
    ('module_path', 'VARCHAR(255) NOT NULL DEFAULT ''/''')
  ) AS columns_to_add(name, definition)
  LOOP
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
      WHERE table_schema = 'iam' AND table_name = 'iam_navigation_app' AND column_name = col.name) THEN
      EXECUTE 'ALTER TABLE iam.iam_navigation_app ADD COLUMN ' || quote_ident(col.name) || ' ' || col.definition;
    END IF;
  END LOOP;
END $$;
COMMENT ON COLUMN iam.iam_navigation_app.domain_name IS 'Browser DNS name only, excluding scheme, port and path; preferred over origin_ip';
COMMENT ON COLUMN iam.iam_navigation_app.public_port IS 'Explicit browser-facing port; NULL uses the public scheme default, independent of origin_port';
COMMENT ON COLUMN iam.iam_navigation_app.origin_scheme IS 'Reverse-proxy transport protocol and IP fallback protocol';
COMMENT ON COLUMN iam.iam_navigation_app.module_path IS 'Application base path at the configured host; not the legacy same-origin gateway prefix';
COMMIT;
