CREATE TABLE IF NOT EXISTS sys_token_revocation (
  token_sha256 VARCHAR(64) PRIMARY KEY,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sys_token_revocation_expiry ON sys_token_revocation(expires_at);
