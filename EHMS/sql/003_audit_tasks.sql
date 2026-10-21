-- Run only in the approved EHM schema with migration credentials before upgrading.
-- Change the schema consistently if the deployment does not use ehm.
CREATE TABLE IF NOT EXISTS ehm.audit_task (
 task_id VARCHAR(64) PRIMARY KEY, owner_id VARCHAR(64) NOT NULL,
 state VARCHAR(32) NOT NULL, base_event TEXT NOT NULL,
 started_at TIMESTAMP WITH TIME ZONE NOT NULL, finished_at TIMESTAMP WITH TIME ZONE,
 lease_until TIMESTAMP WITH TIME ZONE NOT NULL, deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
 error_code VARCHAR(96)
);
CREATE INDEX IF NOT EXISTS idx_ehm_audit_task_state ON ehm.audit_task(state,lease_until);
-- Runtime: SELECT/INSERT plus UPDATE only state, finished_at, lease_until, error_code.
-- Never grant DELETE or UPDATE of the original identity/base_event. No destructive business restore is added.
