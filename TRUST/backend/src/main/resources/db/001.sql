CREATE TABLE trust_users (username VARCHAR(80) PRIMARY KEY, password_hash VARCHAR(100) NOT NULL, role VARCHAR(20) NOT NULL, org_id VARCHAR(80) NOT NULL);
CREATE TABLE evidence (
 id VARCHAR(36) PRIMARY KEY, org_id VARCHAR(80) NOT NULL, uploaded_by VARCHAR(80) NOT NULL,
 filename VARCHAR(255) NOT NULL, mime VARCHAR(80) NOT NULL, size_bytes BIGINT NOT NULL,
 sha256 CHAR(64) NOT NULL, cid VARCHAR(128), storage_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX evidence_org ON evidence(org_id,created_at);
CREATE TABLE events (
 id VARCHAR(36) PRIMARY KEY, org_id VARCHAR(80) NOT NULL, source_system VARCHAR(80) NOT NULL,
 source_event_id VARCHAR(120) NOT NULL, event_type VARCHAR(40) NOT NULL, batch_id VARCHAR(120) NOT NULL,
 object_id VARCHAR(120) NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, submitted_by VARCHAR(80) NOT NULL,
 request_json TEXT NOT NULL, canonical_json TEXT NOT NULL, event_sha256 CHAR(64) NOT NULL,
 root_id VARCHAR(36) NOT NULL, version INTEGER NOT NULL, supersedes_id VARCHAR(36),
 manifest_json TEXT, manifest_cid VARCHAR(128), manifest_sha256 CHAR(64),
 file_state VARCHAR(20) NOT NULL DEFAULT 'PENDING', chain_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 tx_id VARCHAR(128), block_number BIGINT, last_error VARCHAR(2000),
 CONSTRAINT event_source_unique UNIQUE(org_id,source_system,source_event_id),
 CONSTRAINT event_version_unique UNIQUE(root_id,version),
 CONSTRAINT one_correction UNIQUE(supersedes_id)
);
CREATE INDEX events_org_batch ON events(org_id,batch_id,occurred_at);
CREATE INDEX events_org_time ON events(org_id,received_at);
CREATE TABLE event_evidence (event_id VARCHAR(36) REFERENCES events(id), evidence_id VARCHAR(36) REFERENCES evidence(id), PRIMARY KEY(event_id,evidence_id));
CREATE TABLE event_links (event_id VARCHAR(36) REFERENCES events(id), kind VARCHAR(20) NOT NULL, target VARCHAR(220) NOT NULL, PRIMARY KEY(event_id,kind,target));
CREATE INDEX links_lookup ON event_links(kind,target,event_id);
CREATE TABLE tasks (
 event_id VARCHAR(36) PRIMARY KEY REFERENCES events(id), state VARCHAR(20) NOT NULL DEFAULT 'READY',
 attempts INTEGER NOT NULL DEFAULT 0, next_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 lease_token VARCHAR(36), lease_until TIMESTAMP WITH TIME ZONE,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, last_error VARCHAR(2000)
);
CREATE INDEX tasks_due ON tasks(state,next_at);
CREATE TABLE audit_log (id VARCHAR(36) PRIMARY KEY, org_id VARCHAR(80) NOT NULL, actor VARCHAR(80) NOT NULL, action VARCHAR(40) NOT NULL, object_id VARCHAR(120), happened_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, detail VARCHAR(2000));
CREATE INDEX audit_org_time ON audit_log(org_id,happened_at);
