CREATE TABLE IF NOT EXISTS public.sys_audit_outbox (
 id VARCHAR(64) PRIMARY KEY, event_id VARCHAR(128) NOT NULL, payload TEXT NOT NULL,
 payload_hash VARCHAR(64) NOT NULL, state VARCHAR(32) NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0, next_attempt TIMESTAMP WITH TIME ZONE NOT NULL,
 lease_id VARCHAR(64), last_error VARCHAR(96), receipt TEXT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sys_public_audit_event ON public.sys_audit_outbox(event_id);
