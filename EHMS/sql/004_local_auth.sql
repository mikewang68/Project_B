-- Owner/migration account only. Use the same schema/prefix as the private deployment config.
-- No default account/password is inserted. Bootstrap from a protected local file only on an empty store.
CREATE TABLE IF NOT EXISTS ehm.auth_user (
 user_id VARCHAR(64) PRIMARY KEY, username VARCHAR(32) NOT NULL UNIQUE,
 display_name VARCHAR(80) NOT NULL, password_hash VARCHAR(256) NOT NULL,
 role VARCHAR(32) NOT NULL, enabled BOOLEAN NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE IF NOT EXISTS ehm.auth_session (
 session_hash VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64) NOT NULL REFERENCES ehm.auth_user(user_id),
 csrf_token VARCHAR(64) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL, last_seen TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_auth_session_user ON ehm.auth_session(user_id);
CREATE TABLE IF NOT EXISTS ehm.auth_throttle (
 bucket VARCHAR(64) PRIMARY KEY, failures INTEGER NOT NULL, window_start TIMESTAMP WITH TIME ZONE NOT NULL
);
-- Runtime needs SELECT/INSERT/UPDATE on users and throttle; SELECT/INSERT/UPDATE/DELETE on sessions.
-- No permission to delete accounts or immutable audit records. Throttle DELETE only resets its own login counters.
