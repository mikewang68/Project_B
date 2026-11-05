-- Run only after the migration runner confirms these three columns are absent.
-- openGauss A mode does not support ADD COLUMN IF NOT EXISTS; retain additive columns on rollback.
-- Add references only. Existing rows are not presumed to have central authority.
BEGIN;
ALTER TABLE wms_agent_schedule ADD COLUMN scope_context_version SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE wms_agent_schedule ADD COLUMN iam_subject VARCHAR(64);
ALTER TABLE wms_agent_schedule ADD COLUMN iam_session_id VARCHAR(64);
-- V2 references are written only by the authenticated service, not client request fields.
COMMIT;
