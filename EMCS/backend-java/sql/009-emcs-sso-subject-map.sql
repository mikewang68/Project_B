-- Optional EMCS SSO migration 009. Run once on an approved isolated database first.
-- No seed identities or default administrators. Native password/captcha login needs none of these tables.
BEGIN;
CREATE TABLE emcs_sso_subject_map (
  iam_subject varchar(64) NOT NULL,
  audience varchar(32) NOT NULL DEFAULT 'emcs' CHECK (audience='emcs'),
  local_user_id bigint NOT NULL REFERENCES sys_user(user_id),
  enabled smallint NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)),
  read_only smallint NOT NULL DEFAULT 1 CHECK(read_only IN (0,1)),
  scope_area varchar(64) NOT NULL DEFAULT '',
  iam_company varchar(128) NOT NULL DEFAULT '',
  iam_org varchar(128) NOT NULL DEFAULT '',
  iam_domain varchar(128) NOT NULL DEFAULT '',
  iam_zone varchar(128) NOT NULL DEFAULT '',
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(iam_subject,audience),
  UNIQUE(audience,local_user_id)
);
CREATE INDEX idx_emcs_sso_local_user ON emcs_sso_subject_map(local_user_id);
CREATE TABLE emcs_sso_iam_session (
  iam_subject varchar(64) NOT NULL,
  iam_session_id varchar(128) NOT NULL,
  revoked smallint NOT NULL DEFAULT 0 CHECK(revoked IN (0,1)),
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(iam_subject,iam_session_id)
);
CREATE TABLE emcs_sso_session (
  token_hash varchar(64) PRIMARY KEY,
  iam_subject varchar(64) NOT NULL,
  iam_session_id varchar(128) NOT NULL,
  binding_fingerprint varchar(64) NOT NULL,
  revoked smallint NOT NULL DEFAULT 0 CHECK(revoked IN (0,1)),
  expires_at_epoch bigint NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(iam_subject,iam_session_id) REFERENCES emcs_sso_iam_session(iam_subject,iam_session_id)
);
CREATE INDEX idx_emcs_sso_session_iam ON emcs_sso_session(iam_subject,iam_session_id);
COMMIT;
