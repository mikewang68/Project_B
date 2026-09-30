-- Run by the IAM migration role, in the approved IAM-only database.
CREATE TABLE iam.iam_session (
 id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(32) NOT NULL,
 expires_at TIMESTAMP NOT NULL, revoked_at TIMESTAMP
);
CREATE INDEX iam_session_user ON iam.iam_session(user_id);
CREATE TABLE iam.iam_audit (
 id VARCHAR(40) PRIMARY KEY, kind VARCHAR(32), username VARCHAR(64), user_id VARCHAR(32),
 module VARCHAR(32), action VARCHAR(64), target VARCHAR(128), detail TEXT,
 request_method VARCHAR(16), request_uri VARCHAR(256), ip VARCHAR(64), result VARCHAR(16),
 duration_ms INTEGER, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE iam.identity_task (
 user_id VARCHAR(32) PRIMARY KEY, task_id VARCHAR(36) NOT NULL UNIQUE,
 revision BIGINT NOT NULL, org_code VARCHAR(32) NOT NULL, desired_state VARCHAR(16) NOT NULL,
 action VARCHAR(16) NOT NULL DEFAULT 'SYNC', state VARCHAR(32) NOT NULL,
 identity_id VARCHAR(36), public_result TEXT, last_error VARCHAR(64),
 attempts INTEGER NOT NULL DEFAULT 0, next_attempt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 lease_token VARCHAR(36), lease_until TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX identity_task_due ON iam.identity_task(state,next_attempt);
CREATE TABLE iam.user_creation_request (
 request_key VARCHAR(128) PRIMARY KEY, request_hash VARCHAR(64) NOT NULL,
 user_id VARCHAR(32) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE iam.sys_audit_receipt (
 audit_id VARCHAR(40) PRIMARY KEY REFERENCES iam.iam_audit(id),
 received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE iam.identity_operation_request(request_key VARCHAR(128) PRIMARY KEY,user_id VARCHAR(64) NOT NULL,action VARCHAR(16) NOT NULL);
