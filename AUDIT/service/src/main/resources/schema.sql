CREATE TABLE IF NOT EXISTS log_event (
 id VARCHAR(64) PRIMARY KEY, event_id VARCHAR(128) NOT NULL, app_code VARCHAR(64) NOT NULL,
 domain_id VARCHAR(128) NOT NULL, zone_id VARCHAR(128) NOT NULL, user_id VARCHAR(128) NOT NULL,
 menu_id VARCHAR(128) NOT NULL, action VARCHAR(32) NOT NULL, outcome VARCHAR(32) NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL, received_at TIMESTAMP WITH TIME ZONE NOT NULL,
 payload TEXT NOT NULL, payload_hash VARCHAR(64) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_log_scope_time ON log_event(domain_id,zone_id,app_code,occurred_at);
CREATE TABLE IF NOT EXISTS log_chain (
 id VARCHAR(64) PRIMARY KEY REFERENCES log_event(id), state VARCHAR(32) NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0, next_attempt TIMESTAMP WITH TIME ZONE NOT NULL,
 lease_id VARCHAR(64), transaction_id VARCHAR(192), last_error VARCHAR(96)
);
CREATE TABLE IF NOT EXISTS log_trust_delivery (
 id VARCHAR(64) PRIMARY KEY REFERENCES log_event(id), route_id VARCHAR(128) NOT NULL,
 route_fingerprint VARCHAR(64) NOT NULL, request_text TEXT NOT NULL, original_sha256 VARCHAR(64) NOT NULL,
 remote_id VARCHAR(128), remote_status VARCHAR(32), remote_event_sha256 VARCHAR(64), source_reconciled BOOLEAN NOT NULL DEFAULT FALSE, submission_attempted BOOLEAN NOT NULL DEFAULT FALSE,
 outcome_unknown BOOLEAN NOT NULL DEFAULT FALSE, receipt_raw TEXT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, deadline_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE IF NOT EXISTS log_trust_receipt (
 receipt_id VARCHAR(36) PRIMARY KEY, event_key VARCHAR(64) NOT NULL REFERENCES log_event(id),
 raw_receipt TEXT NOT NULL, observed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
