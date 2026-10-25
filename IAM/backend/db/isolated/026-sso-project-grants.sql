BEGIN;
CREATE TABLE IF NOT EXISTS iam.sso_project_grant (
  subject VARCHAR(64) NOT NULL,
  audience VARCHAR(16) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  enabled SMALLINT NOT NULL DEFAULT 1 CHECK(enabled IN(0,1)),
  PRIMARY KEY(subject,audience,project_id)
);
COMMENT ON TABLE iam.sso_project_grant IS 'Explicit application project grants; empty set grants no project access';
COMMIT;
