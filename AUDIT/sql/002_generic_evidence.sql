-- Execute only in the approved AUDIT schema with migration credentials; no shared database auto-DDL.
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
-- Runtime role: delivery INSERT + updates on remote status/deadline columns only;
-- no UPDATE of route identity/request/hash; receipt table INSERT/SELECT only, no DELETE.
