CREATE TABLE fabric_identity (
 id VARCHAR(36) PRIMARY KEY, issuer VARCHAR(80) NOT NULL, tenant VARCHAR(80) NOT NULL,
 user_id VARCHAR(80) NOT NULL, org_id VARCHAR(80) NOT NULL, revision BIGINT NOT NULL,
 command_hash VARCHAR(64) NOT NULL, desired_state VARCHAR(16) NOT NULL, action VARCHAR(16) NOT NULL,
 status VARCHAR(32) NOT NULL, certificate_version INTEGER NOT NULL DEFAULT 0,
 next_attempt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, attempts INTEGER NOT NULL DEFAULT 0,
 lease_token VARCHAR(36), lease_until TIMESTAMP, last_error VARCHAR(64),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(issuer,tenant,user_id)
);
CREATE TABLE fabric_certificate (
 identity_id VARCHAR(36) NOT NULL REFERENCES fabric_identity(id), version INTEGER NOT NULL, requested_revision BIGINT NOT NULL,
 org_id VARCHAR(80) NOT NULL, msp_id VARCHAR(80) NOT NULL, ca_id VARCHAR(80) NOT NULL,
 enrollment_id VARCHAR(80) NOT NULL UNIQUE, key_ref VARCHAR(100) NOT NULL UNIQUE,
 state VARCHAR(32) NOT NULL, fingerprint VARCHAR(64), certificate_pem TEXT,
 not_before TIMESTAMP, not_after TIMESTAMP, network_state VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
 network_receipt TEXT, crl_state VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(identity_id,version)
);
CREATE TABLE identity_request (
 caller_id VARCHAR(80) NOT NULL, request_key VARCHAR(128) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, identity_id VARCHAR(36) NOT NULL REFERENCES fabric_identity(id),
 PRIMARY KEY(caller_id,request_key)
);
CREATE TABLE identity_audit (
 id VARCHAR(36) PRIMARY KEY, identity_id VARCHAR(36) NOT NULL, revision BIGINT NOT NULL,
 actor VARCHAR(80) NOT NULL, action VARCHAR(64) NOT NULL, detail TEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
