-- Execute once with an owner/migration account before upgrading the EHM server.
CREATE TABLE IF NOT EXISTS ehm.audit_outbox (
 id VARCHAR(64) PRIMARY KEY, event_id VARCHAR(128) NOT NULL, payload TEXT NOT NULL,
 payload_hash VARCHAR(64) NOT NULL, state VARCHAR(32) NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0, next_attempt TIMESTAMP WITH TIME ZONE NOT NULL,
 lease_id VARCHAR(64), last_error VARCHAR(96), receipt TEXT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ehm_audit_delivery ON ehm.audit_outbox(state,next_attempt);
-- Grant INSERT, SELECT, UPDATE on this queue to the EHM runtime role (never DELETE).
