-- Historical bindings are deliberately not inferred from current state. They need a new approval.
ALTER TABLE wallet_bindings ADD COLUMN change_request_id VARCHAR(36) REFERENCES wallet_requests(id);
CREATE TABLE event_signing_context (
 event_id VARCHAR(36) PRIMARY KEY REFERENCES events(id),
 context_json TEXT NOT NULL,
 context_sha256 CHAR(64) NOT NULL,
 captured_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE signing_attempts ADD COLUMN context_json TEXT;
ALTER TABLE signing_attempts ADD COLUMN context_sha256 CHAR(64);
ALTER TABLE signing_attempts ADD COLUMN ledger_identity TEXT;
ALTER TABLE signing_attempts ADD COLUMN block_number BIGINT;
ALTER TABLE signing_attempts ADD COLUMN last_error VARCHAR(2000);
ALTER TABLE signing_attempts ADD COLUMN resolved_at TIMESTAMP WITH TIME ZONE;
