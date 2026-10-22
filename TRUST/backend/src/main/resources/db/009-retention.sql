-- Typed immutable versions; mutable heads/runtime are separate. Existing anchors are never rewritten.
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM events e LEFT JOIN events r ON r.id=e.root_id WHERE r.id IS NULL OR r.version<>1 OR r.root_id<>r.id OR r.org_id<>e.org_id OR r.source_system<>e.source_system)
 OR EXISTS (SELECT 1 FROM events e LEFT JOIN events p ON p.id=e.supersedes_id WHERE (e.version=1 AND e.supersedes_id IS NOT NULL) OR (e.version>1 AND (p.id IS NULL OR p.root_id<>e.root_id OR p.version<>e.version-1 OR p.org_id<>e.org_id)))
 OR EXISTS (SELECT 1 FROM event_evidence x JOIN events e ON e.id=x.event_id JOIN evidence v ON v.id=x.evidence_id WHERE e.org_id<>v.org_id)
 THEN RAISE EXCEPTION 'Retention migration refused: invalid version chain or organization reference'; END IF;
END $$;;

CREATE TABLE storage_mutex(resource_key VARCHAR(160) PRIMARY KEY, lock_epoch BIGINT NOT NULL DEFAULT 0);;

CREATE TABLE source_mutex(org_id VARCHAR(80) NOT NULL, source_system VARCHAR(80) NOT NULL, lock_epoch BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(org_id,source_system));;

INSERT INTO source_mutex(org_id,source_system) SELECT org_id,source_system FROM event_source_routes;;

CREATE TABLE event_heads(root_id VARCHAR(36) PRIMARY KEY REFERENCES events(id),org_id VARCHAR(80) NOT NULL,current_id VARCHAR(36) NOT NULL UNIQUE REFERENCES events(id),version INTEGER NOT NULL,lock_epoch BIGINT NOT NULL DEFAULT 0);;

INSERT INTO event_heads(root_id,org_id,current_id,version) SELECT e.root_id,e.org_id,e.id,e.version FROM events e WHERE NOT EXISTS(SELECT 1 FROM events n WHERE n.supersedes_id=e.id);;

ALTER TABLE events ADD COLUMN accepted_actor VARCHAR(160);;

ALTER TABLE events ADD COLUMN accepted_operation VARCHAR(40);;

ALTER TABLE events ADD COLUMN accepted_reason VARCHAR(1000);;

CREATE TABLE events_versions AS SELECT id, manifest_json, manifest_cid, manifest_sha256, file_state, chain_state, tx_id, block_number, last_error, wallet_id, signer_fingerprint, ledger_identity, CAST(1 AS BIGINT) AS retention_revision, CAST(NULL AS BIGINT) AS previous_revision,
 CAST('migration' AS VARCHAR(160)) AS change_actor, CAST('BASELINE' AS VARCHAR(40)) AS change_operation,
 CAST('Existing state at retention migration; earlier snapshots not inferred' AS VARCHAR(1000)) AS change_reason,
 CURRENT_TIMESTAMP AS changed_at FROM events WHERE 1=0;;

ALTER TABLE events_versions ADD PRIMARY KEY(id,retention_revision);;

ALTER TABLE events_versions ALTER COLUMN file_state SET NOT NULL;;

ALTER TABLE events_versions ALTER COLUMN chain_state SET NOT NULL;;

ALTER TABLE events_versions ADD FOREIGN KEY(id) REFERENCES events(id);;

ALTER TABLE events_versions ADD FOREIGN KEY(id,previous_revision) REFERENCES events_versions(id,retention_revision);;

ALTER TABLE events_versions ADD CHECK(retention_revision>0 AND (previous_revision IS NULL OR previous_revision=retention_revision-1));;

INSERT INTO events_versions SELECT id, manifest_json, manifest_cid, manifest_sha256, file_state, chain_state, tx_id, block_number, last_error, wallet_id, signer_fingerprint, ledger_identity,1,NULL,'migration','BASELINE','Existing state at retention migration; earlier snapshots not inferred',CURRENT_TIMESTAMP FROM events;;

CREATE TABLE events_head AS SELECT id,CAST(1 AS BIGINT) AS retention_revision,CAST(0 AS BIGINT) AS lock_epoch FROM events;;

ALTER TABLE events_head ADD PRIMARY KEY(id);;

ALTER TABLE events_head ADD FOREIGN KEY(id,retention_revision) REFERENCES events_versions(id,retention_revision);;

CREATE VIEW events_read AS SELECT b.id, b.org_id, b.source_system, b.source_event_id, b.event_type, b.batch_id, b.object_id, b.occurred_at, b.received_at, b.submitted_by, b.request_json, b.canonical_json, b.event_sha256, b.root_id, b.version, b.supersedes_id, v.manifest_json, v.manifest_cid, v.manifest_sha256, v.file_state, v.chain_state, v.tx_id, v.block_number, v.last_error, b.signing_required, v.wallet_id, v.signer_fingerprint, v.ledger_identity, b.record_kind,v.retention_revision,
 CAST(NULL AS VARCHAR(160)) AS change_actor, CAST(NULL AS VARCHAR(40)) AS change_operation, CAST(NULL AS VARCHAR(1000)) AS change_reason, CAST(NULL AS VARCHAR(36)) AS expected_lease_token
 FROM events b JOIN events_head h ON h.id=b.id JOIN events_versions v ON v.id=h.id AND v.retention_revision=h.retention_revision;;

CREATE FUNCTION events_seed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 BEGIN INSERT INTO events_versions VALUES(NEW.id, NEW.manifest_json, NEW.manifest_cid, NEW.manifest_sha256, NEW.file_state, NEW.chain_state, NEW.tx_id, NEW.block_number, NEW.last_error, NEW.wallet_id, NEW.signer_fingerprint, NEW.ledger_identity,1,NULL,COALESCE(NEW.accepted_actor,NEW.submitted_by),COALESCE(NEW.accepted_operation,'CREATE'),COALESCE(NEW.accepted_reason,'Initial accepted state'),CURRENT_TIMESTAMP);
INSERT INTO events_head(id,retention_revision,lock_epoch) VALUES(NEW.id,1,0);
IF NEW.version=1 AND (NEW.root_id<>NEW.id OR NEW.supersedes_id IS NOT NULL) OR NEW.version<1 THEN RAISE EXCEPTION 'Invalid event version root'; END IF;
 IF NEW.version>1 AND NOT EXISTS(SELECT 1 FROM events p WHERE p.id=NEW.supersedes_id AND p.root_id=NEW.root_id AND p.version=NEW.version-1 AND p.org_id=NEW.org_id AND p.source_system=NEW.source_system) THEN RAISE EXCEPTION 'Invalid predecessor or source organization'; END IF;
 IF NEW.version=1 THEN
 INSERT INTO event_heads(root_id,org_id,current_id,version) VALUES(NEW.id,NEW.org_id,NEW.id,1);
 ELSE
 UPDATE event_heads SET current_id=NEW.id,version=NEW.version WHERE root_id=NEW.root_id AND org_id=NEW.org_id AND current_id=NEW.supersedes_id AND version=NEW.version-1;
 IF NOT FOUND THEN RAISE EXCEPTION 'Event current version conflict' USING ERRCODE='40001'; END IF;
 END IF;
 RETURN NEW; END $$;;

CREATE TRIGGER events_seed AFTER INSERT ON events FOR EACH ROW EXECUTE PROCEDURE events_seed();;

CREATE FUNCTION events_write() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 DECLARE current_row events_head%ROWTYPE; 
 BEGIN IF NEW.expected_lease_token IS NOT NULL THEN
   PERFORM 1 FROM tasks_head WHERE event_id=OLD.id FOR UPDATE;
   IF NOT EXISTS(SELECT 1 FROM tasks_read WHERE event_id=OLD.id AND state='RUNNING' AND lease_token=NEW.expected_lease_token AND lease_until>clock_timestamp()) THEN RETURN NULL; END IF;
   END IF;
IF NEW.id IS DISTINCT FROM OLD.id OR NEW.org_id IS DISTINCT FROM OLD.org_id OR NEW.source_system IS DISTINCT FROM OLD.source_system OR NEW.source_event_id IS DISTINCT FROM OLD.source_event_id OR NEW.event_type IS DISTINCT FROM OLD.event_type OR NEW.batch_id IS DISTINCT FROM OLD.batch_id OR NEW.object_id IS DISTINCT FROM OLD.object_id OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at OR NEW.received_at IS DISTINCT FROM OLD.received_at OR NEW.submitted_by IS DISTINCT FROM OLD.submitted_by OR NEW.request_json IS DISTINCT FROM OLD.request_json OR NEW.canonical_json IS DISTINCT FROM OLD.canonical_json OR NEW.event_sha256 IS DISTINCT FROM OLD.event_sha256 OR NEW.root_id IS DISTINCT FROM OLD.root_id OR NEW.version IS DISTINCT FROM OLD.version OR NEW.supersedes_id IS DISTINCT FROM OLD.supersedes_id OR NEW.signing_required IS DISTINCT FROM OLD.signing_required OR NEW.record_kind IS DISTINCT FROM OLD.record_kind THEN RAISE EXCEPTION 'Immutable business payload cannot be changed'; END IF;
IF OLD.manifest_json IS NOT NULL AND NEW.manifest_json IS DISTINCT FROM OLD.manifest_json THEN RAISE EXCEPTION 'Accepted manifest_json cannot be replaced'; END IF;
IF OLD.manifest_cid IS NOT NULL AND NEW.manifest_cid IS DISTINCT FROM OLD.manifest_cid THEN RAISE EXCEPTION 'Accepted manifest_cid cannot be replaced'; END IF;
IF OLD.manifest_sha256 IS NOT NULL AND NEW.manifest_sha256 IS DISTINCT FROM OLD.manifest_sha256 THEN RAISE EXCEPTION 'Accepted manifest_sha256 cannot be replaced'; END IF;
IF OLD.wallet_id IS NOT NULL AND NEW.wallet_id IS DISTINCT FROM OLD.wallet_id THEN RAISE EXCEPTION 'Accepted wallet_id cannot be replaced'; END IF;
IF OLD.signer_fingerprint IS NOT NULL AND NEW.signer_fingerprint IS DISTINCT FROM OLD.signer_fingerprint THEN RAISE EXCEPTION 'Accepted signer_fingerprint cannot be replaced'; END IF;
IF OLD.chain_state='COMMITTED' AND (NEW.tx_id IS DISTINCT FROM OLD.tx_id OR NEW.chain_state<>'COMMITTED') THEN RAISE EXCEPTION 'Confirmed ledger result cannot be replaced'; END IF;
SELECT * INTO current_row FROM events_head WHERE id=OLD.id FOR UPDATE;
IF NEW.expected_lease_token IS NOT NULL AND NOT EXISTS(SELECT 1 FROM tasks_read WHERE event_id=OLD.id AND state='RUNNING' AND lease_token=NEW.expected_lease_token AND lease_until>clock_timestamp()) THEN RETURN NULL; END IF;
IF current_row.retention_revision<>OLD.retention_revision THEN RAISE EXCEPTION 'Current version changed; read and retry' USING ERRCODE='40001'; END IF;
IF NEW.manifest_json IS DISTINCT FROM OLD.manifest_json OR NEW.manifest_cid IS DISTINCT FROM OLD.manifest_cid OR NEW.manifest_sha256 IS DISTINCT FROM OLD.manifest_sha256 OR NEW.file_state IS DISTINCT FROM OLD.file_state OR NEW.chain_state IS DISTINCT FROM OLD.chain_state OR NEW.tx_id IS DISTINCT FROM OLD.tx_id OR NEW.block_number IS DISTINCT FROM OLD.block_number OR NEW.last_error IS DISTINCT FROM OLD.last_error OR NEW.wallet_id IS DISTINCT FROM OLD.wallet_id OR NEW.signer_fingerprint IS DISTINCT FROM OLD.signer_fingerprint OR NEW.ledger_identity IS DISTINCT FROM OLD.ledger_identity OR NEW.change_operation IS NOT NULL THEN
INSERT INTO events_versions VALUES(NEW.id, NEW.manifest_json, NEW.manifest_cid, NEW.manifest_sha256, NEW.file_state, NEW.chain_state, NEW.tx_id, NEW.block_number, NEW.last_error, NEW.wallet_id, NEW.signer_fingerprint, NEW.ledger_identity,OLD.retention_revision+1,OLD.retention_revision,COALESCE(NEW.change_actor,'system:worker'),COALESCE(NEW.change_operation,'PROCESS'),COALESCE(NEW.change_reason,'Processing state transition'),CURRENT_TIMESTAMP);
UPDATE events_head SET retention_revision=OLD.retention_revision+1 WHERE id=OLD.id;
END IF;
RETURN NEW; END $$;;

CREATE TRIGGER events_write INSTEAD OF UPDATE ON events_read FOR EACH ROW EXECUTE PROCEDURE events_write();;

REVOKE ALL ON FUNCTION events_seed() FROM PUBLIC;;

REVOKE ALL ON FUNCTION events_write() FROM PUBLIC;;

ALTER TABLE evidence ADD COLUMN accepted_actor VARCHAR(160);;

ALTER TABLE evidence ADD COLUMN accepted_operation VARCHAR(40);;

ALTER TABLE evidence ADD COLUMN accepted_reason VARCHAR(1000);;

CREATE TABLE evidence_versions AS SELECT id, cid, storage_state, CAST(1 AS BIGINT) AS retention_revision, CAST(NULL AS BIGINT) AS previous_revision,
 CAST('migration' AS VARCHAR(160)) AS change_actor, CAST('BASELINE' AS VARCHAR(40)) AS change_operation,
 CAST('Existing state at retention migration; earlier snapshots not inferred' AS VARCHAR(1000)) AS change_reason,
 CURRENT_TIMESTAMP AS changed_at FROM evidence WHERE 1=0;;

ALTER TABLE evidence_versions ADD PRIMARY KEY(id,retention_revision);;

ALTER TABLE evidence_versions ALTER COLUMN storage_state SET NOT NULL;;

ALTER TABLE evidence_versions ADD FOREIGN KEY(id) REFERENCES evidence(id);;

ALTER TABLE evidence_versions ADD FOREIGN KEY(id,previous_revision) REFERENCES evidence_versions(id,retention_revision);;

ALTER TABLE evidence_versions ADD CHECK(retention_revision>0 AND (previous_revision IS NULL OR previous_revision=retention_revision-1));;

INSERT INTO evidence_versions SELECT id, cid, storage_state,1,NULL,'migration','BASELINE','Existing state at retention migration; earlier snapshots not inferred',CURRENT_TIMESTAMP FROM evidence;;

CREATE TABLE evidence_head AS SELECT id,CAST(1 AS BIGINT) AS retention_revision,CAST(0 AS BIGINT) AS lock_epoch FROM evidence;;

ALTER TABLE evidence_head ADD PRIMARY KEY(id);;

ALTER TABLE evidence_head ADD FOREIGN KEY(id,retention_revision) REFERENCES evidence_versions(id,retention_revision);;

CREATE VIEW evidence_read AS SELECT b.id, b.org_id, b.uploaded_by, b.filename, b.mime, b.size_bytes, b.sha256, v.cid, v.storage_state, b.created_at,v.retention_revision,
 CAST(NULL AS VARCHAR(160)) AS change_actor, CAST(NULL AS VARCHAR(40)) AS change_operation, CAST(NULL AS VARCHAR(1000)) AS change_reason
 FROM evidence b JOIN evidence_head h ON h.id=b.id JOIN evidence_versions v ON v.id=h.id AND v.retention_revision=h.retention_revision;;

CREATE FUNCTION evidence_seed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 BEGIN INSERT INTO evidence_versions VALUES(NEW.id, NEW.cid, NEW.storage_state,1,NULL,COALESCE(NEW.accepted_actor,NEW.uploaded_by),COALESCE(NEW.accepted_operation,'CREATE'),COALESCE(NEW.accepted_reason,'Initial accepted state'),CURRENT_TIMESTAMP);
INSERT INTO evidence_head(id,retention_revision,lock_epoch) VALUES(NEW.id,1,0);
 RETURN NEW; END $$;;

CREATE TRIGGER evidence_seed AFTER INSERT ON evidence FOR EACH ROW EXECUTE PROCEDURE evidence_seed();;

CREATE FUNCTION evidence_write() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 DECLARE current_row evidence_head%ROWTYPE; 
 BEGIN IF NEW.id IS DISTINCT FROM OLD.id OR NEW.org_id IS DISTINCT FROM OLD.org_id OR NEW.uploaded_by IS DISTINCT FROM OLD.uploaded_by OR NEW.filename IS DISTINCT FROM OLD.filename OR NEW.mime IS DISTINCT FROM OLD.mime OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes OR NEW.sha256 IS DISTINCT FROM OLD.sha256 OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN RAISE EXCEPTION 'Immutable business payload cannot be changed'; END IF;
IF OLD.cid IS NOT NULL AND NEW.cid IS DISTINCT FROM OLD.cid THEN RAISE EXCEPTION 'Accepted cid cannot be replaced'; END IF;
SELECT * INTO current_row FROM evidence_head WHERE id=OLD.id FOR UPDATE;
IF current_row.retention_revision<>OLD.retention_revision THEN RAISE EXCEPTION 'Current version changed; read and retry' USING ERRCODE='40001'; END IF;
IF NEW.cid IS DISTINCT FROM OLD.cid OR NEW.storage_state IS DISTINCT FROM OLD.storage_state OR NEW.change_operation IS NOT NULL THEN
INSERT INTO evidence_versions VALUES(NEW.id, NEW.cid, NEW.storage_state,OLD.retention_revision+1,OLD.retention_revision,COALESCE(NEW.change_actor,'system:worker'),COALESCE(NEW.change_operation,'PROCESS'),COALESCE(NEW.change_reason,'Processing state transition'),CURRENT_TIMESTAMP);
UPDATE evidence_head SET retention_revision=OLD.retention_revision+1 WHERE id=OLD.id;
END IF;
RETURN NEW; END $$;;

CREATE TRIGGER evidence_write INSTEAD OF UPDATE ON evidence_read FOR EACH ROW EXECUTE PROCEDURE evidence_write();;

REVOKE ALL ON FUNCTION evidence_seed() FROM PUBLIC;;

REVOKE ALL ON FUNCTION evidence_write() FROM PUBLIC;;

ALTER TABLE generic_event_data ADD COLUMN accepted_actor VARCHAR(160);;

ALTER TABLE generic_event_data ADD COLUMN accepted_operation VARCHAR(40);;

ALTER TABLE generic_event_data ADD COLUMN accepted_reason VARCHAR(1000);;

CREATE TABLE generic_event_data_versions AS SELECT event_id, original_cid, last_error_code, CAST(1 AS BIGINT) AS retention_revision, CAST(NULL AS BIGINT) AS previous_revision,
 CAST('migration' AS VARCHAR(160)) AS change_actor, CAST('BASELINE' AS VARCHAR(40)) AS change_operation,
 CAST('Existing state at retention migration; earlier snapshots not inferred' AS VARCHAR(1000)) AS change_reason,
 CURRENT_TIMESTAMP AS changed_at FROM generic_event_data WHERE 1=0;;

ALTER TABLE generic_event_data_versions ADD PRIMARY KEY(event_id,retention_revision);;

ALTER TABLE generic_event_data_versions ADD FOREIGN KEY(event_id) REFERENCES generic_event_data(event_id);;

ALTER TABLE generic_event_data_versions ADD FOREIGN KEY(event_id,previous_revision) REFERENCES generic_event_data_versions(event_id,retention_revision);;

ALTER TABLE generic_event_data_versions ADD CHECK(retention_revision>0 AND (previous_revision IS NULL OR previous_revision=retention_revision-1));;

INSERT INTO generic_event_data_versions SELECT event_id, original_cid, last_error_code,1,NULL,'migration','BASELINE','Existing state at retention migration; earlier snapshots not inferred',CURRENT_TIMESTAMP FROM generic_event_data;;

CREATE TABLE generic_event_data_head AS SELECT event_id,CAST(1 AS BIGINT) AS retention_revision,CAST(0 AS BIGINT) AS lock_epoch FROM generic_event_data;;

ALTER TABLE generic_event_data_head ADD PRIMARY KEY(event_id);;

ALTER TABLE generic_event_data_head ADD FOREIGN KEY(event_id,retention_revision) REFERENCES generic_event_data_versions(event_id,retention_revision);;

CREATE VIEW generic_event_data_read AS SELECT b.event_id, b.original_utf8, b.original_sha256, b.original_size_bytes, v.original_cid, v.last_error_code,v.retention_revision,
 CAST(NULL AS VARCHAR(160)) AS change_actor, CAST(NULL AS VARCHAR(40)) AS change_operation, CAST(NULL AS VARCHAR(1000)) AS change_reason, CAST(NULL AS VARCHAR(36)) AS expected_lease_token
 FROM generic_event_data b JOIN generic_event_data_head h ON h.event_id=b.event_id JOIN generic_event_data_versions v ON v.event_id=h.event_id AND v.retention_revision=h.retention_revision;;

CREATE FUNCTION generic_event_data_seed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 BEGIN INSERT INTO generic_event_data_versions VALUES(NEW.event_id, NEW.original_cid, NEW.last_error_code,1,NULL,COALESCE(NEW.accepted_actor,'system:worker'),COALESCE(NEW.accepted_operation,'CREATE'),COALESCE(NEW.accepted_reason,'Initial accepted state'),CURRENT_TIMESTAMP);
INSERT INTO generic_event_data_head(event_id,retention_revision,lock_epoch) VALUES(NEW.event_id,1,0);
 RETURN NEW; END $$;;

CREATE TRIGGER generic_event_data_seed AFTER INSERT ON generic_event_data FOR EACH ROW EXECUTE PROCEDURE generic_event_data_seed();;

CREATE FUNCTION generic_event_data_write() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 DECLARE current_row generic_event_data_head%ROWTYPE; 
 BEGIN IF NEW.expected_lease_token IS NOT NULL THEN
   PERFORM 1 FROM tasks_head WHERE event_id=OLD.event_id FOR UPDATE;
   IF NOT EXISTS(SELECT 1 FROM tasks_read WHERE event_id=OLD.event_id AND state='RUNNING' AND lease_token=NEW.expected_lease_token AND lease_until>clock_timestamp()) THEN RETURN NULL; END IF;
   END IF;
IF NEW.event_id IS DISTINCT FROM OLD.event_id OR NEW.original_utf8 IS DISTINCT FROM OLD.original_utf8 OR NEW.original_sha256 IS DISTINCT FROM OLD.original_sha256 OR NEW.original_size_bytes IS DISTINCT FROM OLD.original_size_bytes THEN RAISE EXCEPTION 'Immutable business payload cannot be changed'; END IF;
IF OLD.original_cid IS NOT NULL AND NEW.original_cid IS DISTINCT FROM OLD.original_cid THEN RAISE EXCEPTION 'Accepted original_cid cannot be replaced'; END IF;
SELECT * INTO current_row FROM generic_event_data_head WHERE event_id=OLD.event_id FOR UPDATE;
IF NEW.expected_lease_token IS NOT NULL AND NOT EXISTS(SELECT 1 FROM tasks_read WHERE event_id=OLD.event_id AND state='RUNNING' AND lease_token=NEW.expected_lease_token AND lease_until>clock_timestamp()) THEN RETURN NULL; END IF;
IF current_row.retention_revision<>OLD.retention_revision THEN RAISE EXCEPTION 'Current version changed; read and retry' USING ERRCODE='40001'; END IF;
IF NEW.original_cid IS DISTINCT FROM OLD.original_cid OR NEW.last_error_code IS DISTINCT FROM OLD.last_error_code OR NEW.change_operation IS NOT NULL THEN
INSERT INTO generic_event_data_versions VALUES(NEW.event_id, NEW.original_cid, NEW.last_error_code,OLD.retention_revision+1,OLD.retention_revision,COALESCE(NEW.change_actor,'system:worker'),COALESCE(NEW.change_operation,'PROCESS'),COALESCE(NEW.change_reason,'Processing state transition'),CURRENT_TIMESTAMP);
UPDATE generic_event_data_head SET retention_revision=OLD.retention_revision+1 WHERE event_id=OLD.event_id;
END IF;
RETURN NEW; END $$;;

CREATE TRIGGER generic_event_data_write INSTEAD OF UPDATE ON generic_event_data_read FOR EACH ROW EXECUTE PROCEDURE generic_event_data_write();;

REVOKE ALL ON FUNCTION generic_event_data_seed() FROM PUBLIC;;

REVOKE ALL ON FUNCTION generic_event_data_write() FROM PUBLIC;;

ALTER TABLE wallets ADD COLUMN accepted_actor VARCHAR(160);;

ALTER TABLE wallets ADD COLUMN accepted_operation VARCHAR(40);;

ALTER TABLE wallets ADD COLUMN accepted_reason VARCHAR(1000);;

CREATE TABLE wallets_versions AS SELECT id, state, CAST(1 AS BIGINT) AS retention_revision, CAST(NULL AS BIGINT) AS previous_revision,
 CAST('migration' AS VARCHAR(160)) AS change_actor, CAST('BASELINE' AS VARCHAR(40)) AS change_operation,
 CAST('Existing state at retention migration; earlier snapshots not inferred' AS VARCHAR(1000)) AS change_reason,
 CURRENT_TIMESTAMP AS changed_at FROM wallets WHERE 1=0;;

ALTER TABLE wallets_versions ADD PRIMARY KEY(id,retention_revision);;

ALTER TABLE wallets_versions ALTER COLUMN state SET NOT NULL;;

ALTER TABLE wallets_versions ADD FOREIGN KEY(id) REFERENCES wallets(id);;

ALTER TABLE wallets_versions ADD FOREIGN KEY(id,previous_revision) REFERENCES wallets_versions(id,retention_revision);;

ALTER TABLE wallets_versions ADD CHECK(retention_revision>0 AND (previous_revision IS NULL OR previous_revision=retention_revision-1));;

INSERT INTO wallets_versions SELECT id, state,1,NULL,'migration','BASELINE','Existing state at retention migration; earlier snapshots not inferred',CURRENT_TIMESTAMP FROM wallets;;

CREATE TABLE wallets_head AS SELECT id,CAST(1 AS BIGINT) AS retention_revision,CAST(0 AS BIGINT) AS lock_epoch FROM wallets;;

ALTER TABLE wallets_head ADD PRIMARY KEY(id);;

ALTER TABLE wallets_head ADD FOREIGN KEY(id,retention_revision) REFERENCES wallets_versions(id,retention_revision);;

CREATE VIEW wallets_read AS SELECT b.id, b.org_id, b.label, b.msp_id, b.key_ref, b.certificate_pem, b.fingerprint, b.subject, b.not_after, v.state, b.created_at,v.retention_revision,
 CAST(NULL AS VARCHAR(160)) AS change_actor, CAST(NULL AS VARCHAR(40)) AS change_operation, CAST(NULL AS VARCHAR(1000)) AS change_reason
 FROM wallets b JOIN wallets_head h ON h.id=b.id JOIN wallets_versions v ON v.id=h.id AND v.retention_revision=h.retention_revision;;

CREATE FUNCTION wallets_seed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 BEGIN INSERT INTO wallets_versions VALUES(NEW.id, NEW.state,1,NULL,COALESCE(NEW.accepted_actor,'system:worker'),COALESCE(NEW.accepted_operation,'CREATE'),COALESCE(NEW.accepted_reason,'Initial accepted state'),CURRENT_TIMESTAMP);
INSERT INTO wallets_head(id,retention_revision,lock_epoch) VALUES(NEW.id,1,0);
 RETURN NEW; END $$;;

CREATE TRIGGER wallets_seed AFTER INSERT ON wallets FOR EACH ROW EXECUTE PROCEDURE wallets_seed();;

CREATE FUNCTION wallets_write() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 DECLARE current_row wallets_head%ROWTYPE; 
 BEGIN IF NEW.id IS DISTINCT FROM OLD.id OR NEW.org_id IS DISTINCT FROM OLD.org_id OR NEW.label IS DISTINCT FROM OLD.label OR NEW.msp_id IS DISTINCT FROM OLD.msp_id OR NEW.key_ref IS DISTINCT FROM OLD.key_ref OR NEW.certificate_pem IS DISTINCT FROM OLD.certificate_pem OR NEW.fingerprint IS DISTINCT FROM OLD.fingerprint OR NEW.subject IS DISTINCT FROM OLD.subject OR NEW.not_after IS DISTINCT FROM OLD.not_after OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN RAISE EXCEPTION 'Immutable business payload cannot be changed'; END IF;
SELECT * INTO current_row FROM wallets_head WHERE id=OLD.id FOR UPDATE;
IF current_row.retention_revision<>OLD.retention_revision THEN RAISE EXCEPTION 'Current version changed; read and retry' USING ERRCODE='40001'; END IF;
IF NEW.state IS DISTINCT FROM OLD.state OR NEW.change_operation IS NOT NULL THEN
INSERT INTO wallets_versions VALUES(NEW.id, NEW.state,OLD.retention_revision+1,OLD.retention_revision,COALESCE(NEW.change_actor,'system:worker'),COALESCE(NEW.change_operation,'PROCESS'),COALESCE(NEW.change_reason,'Processing state transition'),CURRENT_TIMESTAMP);
UPDATE wallets_head SET retention_revision=OLD.retention_revision+1 WHERE id=OLD.id;
END IF;
RETURN NEW; END $$;;

CREATE TRIGGER wallets_write INSTEAD OF UPDATE ON wallets_read FOR EACH ROW EXECUTE PROCEDURE wallets_write();;

REVOKE ALL ON FUNCTION wallets_seed() FROM PUBLIC;;

REVOKE ALL ON FUNCTION wallets_write() FROM PUBLIC;;

ALTER TABLE wallet_bindings ADD COLUMN accepted_actor VARCHAR(160);;

ALTER TABLE wallet_bindings ADD COLUMN accepted_operation VARCHAR(40);;

ALTER TABLE wallet_bindings ADD COLUMN accepted_reason VARCHAR(1000);;

CREATE TABLE wallet_bindings_versions AS SELECT org_id, source_system, wallet_id, revision, change_request_id, CAST(1 AS BIGINT) AS retention_revision, CAST(NULL AS BIGINT) AS previous_revision,
 CAST('migration' AS VARCHAR(160)) AS change_actor, CAST('BASELINE' AS VARCHAR(40)) AS change_operation,
 CAST('Existing state at retention migration; earlier snapshots not inferred' AS VARCHAR(1000)) AS change_reason,
 CURRENT_TIMESTAMP AS changed_at FROM wallet_bindings WHERE 1=0;;

ALTER TABLE wallet_bindings_versions ADD PRIMARY KEY(org_id, source_system,retention_revision);;

ALTER TABLE wallet_bindings_versions ALTER COLUMN wallet_id SET NOT NULL;;

ALTER TABLE wallet_bindings_versions ALTER COLUMN revision SET NOT NULL;;

ALTER TABLE wallet_bindings_versions ADD FOREIGN KEY(org_id, source_system) REFERENCES wallet_bindings(org_id, source_system);;

ALTER TABLE wallet_bindings_versions ADD FOREIGN KEY(org_id, source_system,previous_revision) REFERENCES wallet_bindings_versions(org_id, source_system,retention_revision);;

ALTER TABLE wallet_bindings_versions ADD CHECK(retention_revision>0 AND (previous_revision IS NULL OR previous_revision=retention_revision-1));;

INSERT INTO wallet_bindings_versions SELECT org_id, source_system, wallet_id, revision, change_request_id,1,NULL,'migration','BASELINE','Existing state at retention migration; earlier snapshots not inferred',CURRENT_TIMESTAMP FROM wallet_bindings;;

CREATE TABLE wallet_bindings_head AS SELECT org_id, source_system,CAST(1 AS BIGINT) AS retention_revision,CAST(0 AS BIGINT) AS lock_epoch FROM wallet_bindings;;

ALTER TABLE wallet_bindings_head ADD PRIMARY KEY(org_id, source_system);;

ALTER TABLE wallet_bindings_head ADD FOREIGN KEY(org_id, source_system,retention_revision) REFERENCES wallet_bindings_versions(org_id, source_system,retention_revision);;

CREATE VIEW wallet_bindings_read AS SELECT b.org_id, b.source_system, v.wallet_id, v.revision, v.change_request_id,v.retention_revision,
 CAST(NULL AS VARCHAR(160)) AS change_actor, CAST(NULL AS VARCHAR(40)) AS change_operation, CAST(NULL AS VARCHAR(1000)) AS change_reason
 FROM wallet_bindings b JOIN wallet_bindings_head h ON h.org_id=b.org_id AND h.source_system=b.source_system JOIN wallet_bindings_versions v ON v.org_id=h.org_id AND v.source_system=h.source_system AND v.retention_revision=h.retention_revision;;

CREATE FUNCTION wallet_bindings_seed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 BEGIN INSERT INTO wallet_bindings_versions VALUES(NEW.org_id, NEW.source_system, NEW.wallet_id, NEW.revision, NEW.change_request_id,1,NULL,COALESCE(NEW.accepted_actor,'system:worker'),COALESCE(NEW.accepted_operation,'CREATE'),COALESCE(NEW.accepted_reason,'Initial accepted state'),CURRENT_TIMESTAMP);
INSERT INTO wallet_bindings_head(org_id, source_system,retention_revision,lock_epoch) VALUES(NEW.org_id, NEW.source_system,1,0);
 RETURN NEW; END $$;;

CREATE TRIGGER wallet_bindings_seed AFTER INSERT ON wallet_bindings FOR EACH ROW EXECUTE PROCEDURE wallet_bindings_seed();;

CREATE FUNCTION wallet_bindings_write() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 DECLARE current_row wallet_bindings_head%ROWTYPE; 
 BEGIN IF NEW.org_id IS DISTINCT FROM OLD.org_id OR NEW.source_system IS DISTINCT FROM OLD.source_system THEN RAISE EXCEPTION 'Immutable business payload cannot be changed'; END IF;
SELECT * INTO current_row FROM wallet_bindings_head WHERE org_id=OLD.org_id AND source_system=OLD.source_system FOR UPDATE;
IF current_row.retention_revision<>OLD.retention_revision THEN RAISE EXCEPTION 'Current version changed; read and retry' USING ERRCODE='40001'; END IF;
IF NEW.wallet_id IS DISTINCT FROM OLD.wallet_id OR NEW.revision IS DISTINCT FROM OLD.revision OR NEW.change_request_id IS DISTINCT FROM OLD.change_request_id OR NEW.change_operation IS NOT NULL THEN
INSERT INTO wallet_bindings_versions VALUES(NEW.org_id, NEW.source_system, NEW.wallet_id, NEW.revision, NEW.change_request_id,OLD.retention_revision+1,OLD.retention_revision,COALESCE(NEW.change_actor,'system:worker'),COALESCE(NEW.change_operation,'PROCESS'),COALESCE(NEW.change_reason,'Processing state transition'),CURRENT_TIMESTAMP);
UPDATE wallet_bindings_head SET retention_revision=OLD.retention_revision+1 WHERE org_id=OLD.org_id AND source_system=OLD.source_system;
END IF;
RETURN NEW; END $$;;

CREATE TRIGGER wallet_bindings_write INSTEAD OF UPDATE ON wallet_bindings_read FOR EACH ROW EXECUTE PROCEDURE wallet_bindings_write();;

REVOKE ALL ON FUNCTION wallet_bindings_seed() FROM PUBLIC;;

REVOKE ALL ON FUNCTION wallet_bindings_write() FROM PUBLIC;;

ALTER TABLE wallet_requests ADD COLUMN accepted_actor VARCHAR(160);;

ALTER TABLE wallet_requests ADD COLUMN accepted_operation VARCHAR(40);;

ALTER TABLE wallet_requests ADD COLUMN accepted_reason VARCHAR(1000);;

CREATE TABLE wallet_requests_versions AS SELECT id, reviewed_by, state, reviewed_at, CAST(1 AS BIGINT) AS retention_revision, CAST(NULL AS BIGINT) AS previous_revision,
 CAST('migration' AS VARCHAR(160)) AS change_actor, CAST('BASELINE' AS VARCHAR(40)) AS change_operation,
 CAST('Existing state at retention migration; earlier snapshots not inferred' AS VARCHAR(1000)) AS change_reason,
 CURRENT_TIMESTAMP AS changed_at FROM wallet_requests WHERE 1=0;;

ALTER TABLE wallet_requests_versions ADD PRIMARY KEY(id,retention_revision);;

ALTER TABLE wallet_requests_versions ALTER COLUMN state SET NOT NULL;;

ALTER TABLE wallet_requests_versions ADD FOREIGN KEY(id) REFERENCES wallet_requests(id);;

ALTER TABLE wallet_requests_versions ADD FOREIGN KEY(id,previous_revision) REFERENCES wallet_requests_versions(id,retention_revision);;

ALTER TABLE wallet_requests_versions ADD CHECK(retention_revision>0 AND (previous_revision IS NULL OR previous_revision=retention_revision-1));;

INSERT INTO wallet_requests_versions SELECT id, reviewed_by, state, reviewed_at,1,NULL,'migration','BASELINE','Existing state at retention migration; earlier snapshots not inferred',CURRENT_TIMESTAMP FROM wallet_requests;;

CREATE TABLE wallet_requests_head AS SELECT id,CAST(1 AS BIGINT) AS retention_revision,CAST(0 AS BIGINT) AS lock_epoch FROM wallet_requests;;

ALTER TABLE wallet_requests_head ADD PRIMARY KEY(id);;

ALTER TABLE wallet_requests_head ADD FOREIGN KEY(id,retention_revision) REFERENCES wallet_requests_versions(id,retention_revision);;

CREATE VIEW wallet_requests_read AS SELECT b.id, b.org_id, b.action, b.payload, b.reason, b.proposed_by, v.reviewed_by, v.state, b.created_at, v.reviewed_at,v.retention_revision,
 CAST(NULL AS VARCHAR(160)) AS change_actor, CAST(NULL AS VARCHAR(40)) AS change_operation, CAST(NULL AS VARCHAR(1000)) AS change_reason
 FROM wallet_requests b JOIN wallet_requests_head h ON h.id=b.id JOIN wallet_requests_versions v ON v.id=h.id AND v.retention_revision=h.retention_revision;;

CREATE FUNCTION wallet_requests_seed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 BEGIN INSERT INTO wallet_requests_versions VALUES(NEW.id, NEW.reviewed_by, NEW.state, NEW.reviewed_at,1,NULL,COALESCE(NEW.accepted_actor,NEW.proposed_by),COALESCE(NEW.accepted_operation,'CREATE'),COALESCE(NEW.accepted_reason,'Initial accepted state'),CURRENT_TIMESTAMP);
INSERT INTO wallet_requests_head(id,retention_revision,lock_epoch) VALUES(NEW.id,1,0);
 RETURN NEW; END $$;;

CREATE TRIGGER wallet_requests_seed AFTER INSERT ON wallet_requests FOR EACH ROW EXECUTE PROCEDURE wallet_requests_seed();;

CREATE FUNCTION wallet_requests_write() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 DECLARE current_row wallet_requests_head%ROWTYPE; 
 BEGIN IF NEW.id IS DISTINCT FROM OLD.id OR NEW.org_id IS DISTINCT FROM OLD.org_id OR NEW.action IS DISTINCT FROM OLD.action OR NEW.payload IS DISTINCT FROM OLD.payload OR NEW.reason IS DISTINCT FROM OLD.reason OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN RAISE EXCEPTION 'Immutable business payload cannot be changed'; END IF;
IF OLD.state<>'PENDING' AND (NEW.state IS DISTINCT FROM OLD.state OR NEW.reviewed_by IS DISTINCT FROM OLD.reviewed_by OR NEW.reviewed_at IS DISTINCT FROM OLD.reviewed_at) THEN RAISE EXCEPTION 'Review decision already finalized'; END IF;
SELECT * INTO current_row FROM wallet_requests_head WHERE id=OLD.id FOR UPDATE;
IF current_row.retention_revision<>OLD.retention_revision THEN RAISE EXCEPTION 'Current version changed; read and retry' USING ERRCODE='40001'; END IF;
IF NEW.reviewed_by IS DISTINCT FROM OLD.reviewed_by OR NEW.state IS DISTINCT FROM OLD.state OR NEW.reviewed_at IS DISTINCT FROM OLD.reviewed_at OR NEW.change_operation IS NOT NULL THEN
INSERT INTO wallet_requests_versions VALUES(NEW.id, NEW.reviewed_by, NEW.state, NEW.reviewed_at,OLD.retention_revision+1,OLD.retention_revision,COALESCE(NEW.change_actor,'system:worker'),COALESCE(NEW.change_operation,'PROCESS'),COALESCE(NEW.change_reason,'Processing state transition'),CURRENT_TIMESTAMP);
UPDATE wallet_requests_head SET retention_revision=OLD.retention_revision+1 WHERE id=OLD.id;
END IF;
RETURN NEW; END $$;;

CREATE TRIGGER wallet_requests_write INSTEAD OF UPDATE ON wallet_requests_read FOR EACH ROW EXECUTE PROCEDURE wallet_requests_write();;

REVOKE ALL ON FUNCTION wallet_requests_seed() FROM PUBLIC;;

REVOKE ALL ON FUNCTION wallet_requests_write() FROM PUBLIC;;

ALTER TABLE fabric_identity ADD COLUMN accepted_actor VARCHAR(160);;

ALTER TABLE fabric_identity ADD COLUMN accepted_operation VARCHAR(40);;

ALTER TABLE fabric_identity ADD COLUMN accepted_reason VARCHAR(1000);;

CREATE TABLE fabric_identity_versions AS SELECT id, org_id, revision, command_hash, desired_state, action, status, certificate_version, last_error, CAST(1 AS BIGINT) AS retention_revision, CAST(NULL AS BIGINT) AS previous_revision,
 CAST('migration' AS VARCHAR(160)) AS change_actor, CAST('BASELINE' AS VARCHAR(40)) AS change_operation,
 CAST('Existing state at retention migration; earlier snapshots not inferred' AS VARCHAR(1000)) AS change_reason,
 CURRENT_TIMESTAMP AS changed_at FROM fabric_identity WHERE 1=0;;

ALTER TABLE fabric_identity_versions ADD PRIMARY KEY(id,retention_revision);;

ALTER TABLE fabric_identity_versions ALTER COLUMN org_id SET NOT NULL;;

ALTER TABLE fabric_identity_versions ALTER COLUMN revision SET NOT NULL;;

ALTER TABLE fabric_identity_versions ALTER COLUMN command_hash SET NOT NULL;;

ALTER TABLE fabric_identity_versions ALTER COLUMN desired_state SET NOT NULL;;

ALTER TABLE fabric_identity_versions ALTER COLUMN action SET NOT NULL;;

ALTER TABLE fabric_identity_versions ALTER COLUMN status SET NOT NULL;;

ALTER TABLE fabric_identity_versions ALTER COLUMN certificate_version SET NOT NULL;;

ALTER TABLE fabric_identity_versions ADD FOREIGN KEY(id) REFERENCES fabric_identity(id);;

ALTER TABLE fabric_identity_versions ADD FOREIGN KEY(id,previous_revision) REFERENCES fabric_identity_versions(id,retention_revision);;

ALTER TABLE fabric_identity_versions ADD CHECK(retention_revision>0 AND (previous_revision IS NULL OR previous_revision=retention_revision-1));;

INSERT INTO fabric_identity_versions SELECT id, org_id, revision, command_hash, desired_state, action, status, certificate_version, last_error,1,NULL,'migration','BASELINE','Existing state at retention migration; earlier snapshots not inferred',CURRENT_TIMESTAMP FROM fabric_identity;;

CREATE TABLE fabric_identity_head AS SELECT id,CAST(1 AS BIGINT) AS retention_revision,CAST(0 AS BIGINT) AS lock_epoch FROM fabric_identity;;

ALTER TABLE fabric_identity_head ADD PRIMARY KEY(id);;

ALTER TABLE fabric_identity_head ADD FOREIGN KEY(id,retention_revision) REFERENCES fabric_identity_versions(id,retention_revision);;

CREATE TABLE fabric_identity_runtime AS SELECT id, next_attempt, attempts, lease_token, lease_until, updated_at FROM fabric_identity;;

ALTER TABLE fabric_identity_runtime ADD PRIMARY KEY(id);;

ALTER TABLE fabric_identity_runtime ADD FOREIGN KEY(id) REFERENCES fabric_identity(id);;

CREATE INDEX fabric_identity_runtime_due ON fabric_identity_runtime(next_attempt);;

CREATE VIEW fabric_identity_read AS SELECT b.id, b.issuer, b.tenant, b.user_id, v.org_id, v.revision, v.command_hash, v.desired_state, v.action, v.status, v.certificate_version, r.next_attempt, r.attempts, r.lease_token, r.lease_until, v.last_error, b.created_at, r.updated_at,v.retention_revision,
 CAST(NULL AS VARCHAR(160)) AS change_actor, CAST(NULL AS VARCHAR(40)) AS change_operation, CAST(NULL AS VARCHAR(1000)) AS change_reason
 FROM fabric_identity b JOIN fabric_identity_head h ON h.id=b.id JOIN fabric_identity_versions v ON v.id=h.id AND v.retention_revision=h.retention_revision JOIN fabric_identity_runtime r ON r.id=b.id;;

CREATE FUNCTION fabric_identity_seed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 BEGIN INSERT INTO fabric_identity_versions VALUES(NEW.id, NEW.org_id, NEW.revision, NEW.command_hash, NEW.desired_state, NEW.action, NEW.status, NEW.certificate_version, NEW.last_error,1,NULL,COALESCE(NEW.accepted_actor,'system:worker'),COALESCE(NEW.accepted_operation,'CREATE'),COALESCE(NEW.accepted_reason,'Initial accepted state'),CURRENT_TIMESTAMP);
INSERT INTO fabric_identity_head(id,retention_revision,lock_epoch) VALUES(NEW.id,1,0);
INSERT INTO fabric_identity_runtime VALUES(NEW.id, NEW.next_attempt, NEW.attempts, NEW.lease_token, NEW.lease_until, NEW.updated_at);
 RETURN NEW; END $$;;

CREATE TRIGGER fabric_identity_seed AFTER INSERT ON fabric_identity FOR EACH ROW EXECUTE PROCEDURE fabric_identity_seed();;

CREATE FUNCTION fabric_identity_write() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 DECLARE current_row fabric_identity_head%ROWTYPE; running fabric_identity_runtime%ROWTYPE;
 BEGIN IF NEW.id IS DISTINCT FROM OLD.id OR NEW.issuer IS DISTINCT FROM OLD.issuer OR NEW.tenant IS DISTINCT FROM OLD.tenant OR NEW.user_id IS DISTINCT FROM OLD.user_id OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN RAISE EXCEPTION 'Immutable business payload cannot be changed'; END IF;
IF OLD.desired_state='DELETED' AND NEW.desired_state<>'DELETED' THEN RAISE EXCEPTION 'Deleted identity cannot be restored'; END IF;
SELECT * INTO current_row FROM fabric_identity_head WHERE id=OLD.id FOR UPDATE;
IF current_row.retention_revision<>OLD.retention_revision THEN RAISE EXCEPTION 'Current version changed; read and retry' USING ERRCODE='40001'; END IF;
SELECT * INTO running FROM fabric_identity_runtime WHERE id=OLD.id;
IF NEW.next_attempt IS DISTINCT FROM OLD.next_attempt AND running.next_attempt IS DISTINCT FROM OLD.next_attempt THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF NEW.attempts IS DISTINCT FROM OLD.attempts AND running.attempts IS DISTINCT FROM OLD.attempts THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF NEW.lease_token IS DISTINCT FROM OLD.lease_token AND running.lease_token IS DISTINCT FROM OLD.lease_token THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF NEW.lease_until IS DISTINCT FROM OLD.lease_until AND running.lease_until IS DISTINCT FROM OLD.lease_until THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF NEW.updated_at IS DISTINCT FROM OLD.updated_at AND running.updated_at IS DISTINCT FROM OLD.updated_at THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF running.lease_token IS DISTINCT FROM OLD.lease_token THEN RAISE EXCEPTION 'Worker ownership changed' USING ERRCODE='40001'; END IF;
IF OLD.lease_token IS NOT NULL AND running.lease_token=OLD.lease_token AND OLD.lease_until>statement_timestamp() AND running.lease_until<=clock_timestamp() THEN RAISE EXCEPTION 'Worker lease expired' USING ERRCODE='40001'; END IF;
IF NEW.org_id IS DISTINCT FROM OLD.org_id OR NEW.revision IS DISTINCT FROM OLD.revision OR NEW.command_hash IS DISTINCT FROM OLD.command_hash OR NEW.desired_state IS DISTINCT FROM OLD.desired_state OR NEW.action IS DISTINCT FROM OLD.action OR NEW.status IS DISTINCT FROM OLD.status OR NEW.certificate_version IS DISTINCT FROM OLD.certificate_version OR NEW.last_error IS DISTINCT FROM OLD.last_error OR NEW.change_operation IS NOT NULL THEN
INSERT INTO fabric_identity_versions VALUES(NEW.id, NEW.org_id, NEW.revision, NEW.command_hash, NEW.desired_state, NEW.action, NEW.status, NEW.certificate_version, NEW.last_error,OLD.retention_revision+1,OLD.retention_revision,COALESCE(NEW.change_actor,'system:worker'),COALESCE(NEW.change_operation,'PROCESS'),COALESCE(NEW.change_reason,'Processing state transition'),CURRENT_TIMESTAMP);
UPDATE fabric_identity_head SET retention_revision=OLD.retention_revision+1 WHERE id=OLD.id;
END IF;
UPDATE fabric_identity_runtime SET next_attempt=CASE WHEN NEW.next_attempt IS DISTINCT FROM OLD.next_attempt THEN NEW.next_attempt ELSE running.next_attempt END, attempts=CASE WHEN NEW.attempts IS DISTINCT FROM OLD.attempts THEN NEW.attempts ELSE running.attempts END, lease_token=CASE WHEN NEW.lease_token IS DISTINCT FROM OLD.lease_token THEN NEW.lease_token ELSE running.lease_token END, lease_until=CASE WHEN NEW.lease_until IS DISTINCT FROM OLD.lease_until THEN NEW.lease_until ELSE running.lease_until END, updated_at=CASE WHEN NEW.updated_at IS DISTINCT FROM OLD.updated_at THEN NEW.updated_at ELSE running.updated_at END WHERE id=OLD.id;
RETURN NEW; END $$;;

CREATE TRIGGER fabric_identity_write INSTEAD OF UPDATE ON fabric_identity_read FOR EACH ROW EXECUTE PROCEDURE fabric_identity_write();;

REVOKE ALL ON FUNCTION fabric_identity_seed() FROM PUBLIC;;

REVOKE ALL ON FUNCTION fabric_identity_write() FROM PUBLIC;;

ALTER TABLE fabric_certificate ADD COLUMN accepted_actor VARCHAR(160);;

ALTER TABLE fabric_certificate ADD COLUMN accepted_operation VARCHAR(40);;

ALTER TABLE fabric_certificate ADD COLUMN accepted_reason VARCHAR(1000);;

CREATE TABLE fabric_certificate_versions AS SELECT identity_id, version, state, fingerprint, certificate_pem, not_before, not_after, network_state, network_receipt, crl_state, CAST(1 AS BIGINT) AS retention_revision, CAST(NULL AS BIGINT) AS previous_revision,
 CAST('migration' AS VARCHAR(160)) AS change_actor, CAST('BASELINE' AS VARCHAR(40)) AS change_operation,
 CAST('Existing state at retention migration; earlier snapshots not inferred' AS VARCHAR(1000)) AS change_reason,
 CURRENT_TIMESTAMP AS changed_at FROM fabric_certificate WHERE 1=0;;

ALTER TABLE fabric_certificate_versions ADD PRIMARY KEY(identity_id, version,retention_revision);;

ALTER TABLE fabric_certificate_versions ALTER COLUMN state SET NOT NULL;;

ALTER TABLE fabric_certificate_versions ALTER COLUMN network_state SET NOT NULL;;

ALTER TABLE fabric_certificate_versions ALTER COLUMN crl_state SET NOT NULL;;

ALTER TABLE fabric_certificate_versions ADD FOREIGN KEY(identity_id, version) REFERENCES fabric_certificate(identity_id, version);;

ALTER TABLE fabric_certificate_versions ADD FOREIGN KEY(identity_id, version,previous_revision) REFERENCES fabric_certificate_versions(identity_id, version,retention_revision);;

ALTER TABLE fabric_certificate_versions ADD CHECK(retention_revision>0 AND (previous_revision IS NULL OR previous_revision=retention_revision-1));;

INSERT INTO fabric_certificate_versions SELECT identity_id, version, state, fingerprint, certificate_pem, not_before, not_after, network_state, network_receipt, crl_state,1,NULL,'migration','BASELINE','Existing state at retention migration; earlier snapshots not inferred',CURRENT_TIMESTAMP FROM fabric_certificate;;

CREATE TABLE fabric_certificate_head AS SELECT identity_id, version,CAST(1 AS BIGINT) AS retention_revision,CAST(0 AS BIGINT) AS lock_epoch FROM fabric_certificate;;

ALTER TABLE fabric_certificate_head ADD PRIMARY KEY(identity_id, version);;

ALTER TABLE fabric_certificate_head ADD FOREIGN KEY(identity_id, version,retention_revision) REFERENCES fabric_certificate_versions(identity_id, version,retention_revision);;

CREATE VIEW fabric_certificate_read AS SELECT b.identity_id, b.version, b.requested_revision, b.org_id, b.msp_id, b.ca_id, b.enrollment_id, b.key_ref, v.state, v.fingerprint, v.certificate_pem, v.not_before, v.not_after, v.network_state, v.network_receipt, v.crl_state, b.created_at,v.retention_revision,
 CAST(NULL AS VARCHAR(160)) AS change_actor, CAST(NULL AS VARCHAR(40)) AS change_operation, CAST(NULL AS VARCHAR(1000)) AS change_reason
 FROM fabric_certificate b JOIN fabric_certificate_head h ON h.identity_id=b.identity_id AND h.version=b.version JOIN fabric_certificate_versions v ON v.identity_id=h.identity_id AND v.version=h.version AND v.retention_revision=h.retention_revision;;

CREATE FUNCTION fabric_certificate_seed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 BEGIN INSERT INTO fabric_certificate_versions VALUES(NEW.identity_id, NEW.version, NEW.state, NEW.fingerprint, NEW.certificate_pem, NEW.not_before, NEW.not_after, NEW.network_state, NEW.network_receipt, NEW.crl_state,1,NULL,COALESCE(NEW.accepted_actor,'system:worker'),COALESCE(NEW.accepted_operation,'CREATE'),COALESCE(NEW.accepted_reason,'Initial accepted state'),CURRENT_TIMESTAMP);
INSERT INTO fabric_certificate_head(identity_id, version,retention_revision,lock_epoch) VALUES(NEW.identity_id, NEW.version,1,0);
 RETURN NEW; END $$;;

CREATE TRIGGER fabric_certificate_seed AFTER INSERT ON fabric_certificate FOR EACH ROW EXECUTE PROCEDURE fabric_certificate_seed();;

CREATE FUNCTION fabric_certificate_write() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 DECLARE current_row fabric_certificate_head%ROWTYPE; 
 BEGIN IF NEW.identity_id IS DISTINCT FROM OLD.identity_id OR NEW.version IS DISTINCT FROM OLD.version OR NEW.requested_revision IS DISTINCT FROM OLD.requested_revision OR NEW.org_id IS DISTINCT FROM OLD.org_id OR NEW.msp_id IS DISTINCT FROM OLD.msp_id OR NEW.ca_id IS DISTINCT FROM OLD.ca_id OR NEW.enrollment_id IS DISTINCT FROM OLD.enrollment_id OR NEW.key_ref IS DISTINCT FROM OLD.key_ref OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN RAISE EXCEPTION 'Immutable business payload cannot be changed'; END IF;
IF OLD.fingerprint IS NOT NULL AND NEW.fingerprint IS DISTINCT FROM OLD.fingerprint THEN RAISE EXCEPTION 'Accepted fingerprint cannot be replaced'; END IF;
IF OLD.certificate_pem IS NOT NULL AND NEW.certificate_pem IS DISTINCT FROM OLD.certificate_pem THEN RAISE EXCEPTION 'Accepted certificate_pem cannot be replaced'; END IF;
IF OLD.not_before IS NOT NULL AND NEW.not_before IS DISTINCT FROM OLD.not_before THEN RAISE EXCEPTION 'Accepted not_before cannot be replaced'; END IF;
IF OLD.not_after IS NOT NULL AND NEW.not_after IS DISTINCT FROM OLD.not_after THEN RAISE EXCEPTION 'Accepted not_after cannot be replaced'; END IF;
SELECT * INTO current_row FROM fabric_certificate_head WHERE identity_id=OLD.identity_id AND version=OLD.version FOR UPDATE;
IF current_row.retention_revision<>OLD.retention_revision THEN RAISE EXCEPTION 'Current version changed; read and retry' USING ERRCODE='40001'; END IF;
IF NEW.state IS DISTINCT FROM OLD.state OR NEW.fingerprint IS DISTINCT FROM OLD.fingerprint OR NEW.certificate_pem IS DISTINCT FROM OLD.certificate_pem OR NEW.not_before IS DISTINCT FROM OLD.not_before OR NEW.not_after IS DISTINCT FROM OLD.not_after OR NEW.network_state IS DISTINCT FROM OLD.network_state OR NEW.network_receipt IS DISTINCT FROM OLD.network_receipt OR NEW.crl_state IS DISTINCT FROM OLD.crl_state OR NEW.change_operation IS NOT NULL THEN
INSERT INTO fabric_certificate_versions VALUES(NEW.identity_id, NEW.version, NEW.state, NEW.fingerprint, NEW.certificate_pem, NEW.not_before, NEW.not_after, NEW.network_state, NEW.network_receipt, NEW.crl_state,OLD.retention_revision+1,OLD.retention_revision,COALESCE(NEW.change_actor,'system:worker'),COALESCE(NEW.change_operation,'PROCESS'),COALESCE(NEW.change_reason,'Processing state transition'),CURRENT_TIMESTAMP);
UPDATE fabric_certificate_head SET retention_revision=OLD.retention_revision+1 WHERE identity_id=OLD.identity_id AND version=OLD.version;
END IF;
RETURN NEW; END $$;;

CREATE TRIGGER fabric_certificate_write INSTEAD OF UPDATE ON fabric_certificate_read FOR EACH ROW EXECUTE PROCEDURE fabric_certificate_write();;

REVOKE ALL ON FUNCTION fabric_certificate_seed() FROM PUBLIC;;

REVOKE ALL ON FUNCTION fabric_certificate_write() FROM PUBLIC;;

ALTER TABLE audit_log_settings ADD COLUMN accepted_actor VARCHAR(160);;

ALTER TABLE audit_log_settings ADD COLUMN accepted_operation VARCHAR(40);;

ALTER TABLE audit_log_settings ADD COLUMN accepted_reason VARCHAR(1000);;

CREATE TABLE audit_log_settings_versions AS SELECT id, archive_paused, chain_paused, updated_at, CAST(1 AS BIGINT) AS retention_revision, CAST(NULL AS BIGINT) AS previous_revision,
 CAST('migration' AS VARCHAR(160)) AS change_actor, CAST('BASELINE' AS VARCHAR(40)) AS change_operation,
 CAST('Existing state at retention migration; earlier snapshots not inferred' AS VARCHAR(1000)) AS change_reason,
 CURRENT_TIMESTAMP AS changed_at FROM audit_log_settings WHERE 1=0;;

ALTER TABLE audit_log_settings_versions ADD PRIMARY KEY(id,retention_revision);;

ALTER TABLE audit_log_settings_versions ALTER COLUMN archive_paused SET NOT NULL;;

ALTER TABLE audit_log_settings_versions ALTER COLUMN chain_paused SET NOT NULL;;

ALTER TABLE audit_log_settings_versions ALTER COLUMN updated_at SET NOT NULL;;

ALTER TABLE audit_log_settings_versions ADD FOREIGN KEY(id) REFERENCES audit_log_settings(id);;

ALTER TABLE audit_log_settings_versions ADD FOREIGN KEY(id,previous_revision) REFERENCES audit_log_settings_versions(id,retention_revision);;

ALTER TABLE audit_log_settings_versions ADD CHECK(retention_revision>0 AND (previous_revision IS NULL OR previous_revision=retention_revision-1));;

INSERT INTO audit_log_settings_versions SELECT id, archive_paused, chain_paused, updated_at,1,NULL,'migration','BASELINE','Existing state at retention migration; earlier snapshots not inferred',CURRENT_TIMESTAMP FROM audit_log_settings;;

CREATE TABLE audit_log_settings_head AS SELECT id,CAST(1 AS BIGINT) AS retention_revision,CAST(0 AS BIGINT) AS lock_epoch FROM audit_log_settings;;

ALTER TABLE audit_log_settings_head ADD PRIMARY KEY(id);;

ALTER TABLE audit_log_settings_head ADD FOREIGN KEY(id,retention_revision) REFERENCES audit_log_settings_versions(id,retention_revision);;

CREATE VIEW audit_log_settings_read AS SELECT b.id, v.archive_paused, v.chain_paused, v.updated_at,v.retention_revision,
 CAST(NULL AS VARCHAR(160)) AS change_actor, CAST(NULL AS VARCHAR(40)) AS change_operation, CAST(NULL AS VARCHAR(1000)) AS change_reason
 FROM audit_log_settings b JOIN audit_log_settings_head h ON h.id=b.id JOIN audit_log_settings_versions v ON v.id=h.id AND v.retention_revision=h.retention_revision;;

CREATE FUNCTION audit_log_settings_seed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 BEGIN INSERT INTO audit_log_settings_versions VALUES(NEW.id, NEW.archive_paused, NEW.chain_paused, NEW.updated_at,1,NULL,COALESCE(NEW.accepted_actor,'system:worker'),COALESCE(NEW.accepted_operation,'CREATE'),COALESCE(NEW.accepted_reason,'Initial accepted state'),CURRENT_TIMESTAMP);
INSERT INTO audit_log_settings_head(id,retention_revision,lock_epoch) VALUES(NEW.id,1,0);
 RETURN NEW; END $$;;

CREATE TRIGGER audit_log_settings_seed AFTER INSERT ON audit_log_settings FOR EACH ROW EXECUTE PROCEDURE audit_log_settings_seed();;

CREATE FUNCTION audit_log_settings_write() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 DECLARE current_row audit_log_settings_head%ROWTYPE; 
 BEGIN IF NEW.id IS DISTINCT FROM OLD.id THEN RAISE EXCEPTION 'Immutable business payload cannot be changed'; END IF;
SELECT * INTO current_row FROM audit_log_settings_head WHERE id=OLD.id FOR UPDATE;
IF current_row.retention_revision<>OLD.retention_revision THEN RAISE EXCEPTION 'Current version changed; read and retry' USING ERRCODE='40001'; END IF;
IF NEW.archive_paused IS DISTINCT FROM OLD.archive_paused OR NEW.chain_paused IS DISTINCT FROM OLD.chain_paused OR NEW.updated_at IS DISTINCT FROM OLD.updated_at OR NEW.change_operation IS NOT NULL THEN
INSERT INTO audit_log_settings_versions VALUES(NEW.id, NEW.archive_paused, NEW.chain_paused, NEW.updated_at,OLD.retention_revision+1,OLD.retention_revision,COALESCE(NEW.change_actor,'system:worker'),COALESCE(NEW.change_operation,'PROCESS'),COALESCE(NEW.change_reason,'Processing state transition'),CURRENT_TIMESTAMP);
UPDATE audit_log_settings_head SET retention_revision=OLD.retention_revision+1 WHERE id=OLD.id;
END IF;
RETURN NEW; END $$;;

CREATE TRIGGER audit_log_settings_write INSTEAD OF UPDATE ON audit_log_settings_read FOR EACH ROW EXECUTE PROCEDURE audit_log_settings_write();;

REVOKE ALL ON FUNCTION audit_log_settings_seed() FROM PUBLIC;;

REVOKE ALL ON FUNCTION audit_log_settings_write() FROM PUBLIC;;

ALTER TABLE audit_evidence ADD COLUMN accepted_actor VARCHAR(160);;

ALTER TABLE audit_evidence ADD COLUMN accepted_operation VARCHAR(40);;

ALTER TABLE audit_evidence ADD COLUMN accepted_reason VARCHAR(1000);;

CREATE TABLE audit_evidence_versions AS SELECT event_key, archive_cid, archive_state, chain_event_id, last_error, CAST(1 AS BIGINT) AS retention_revision, CAST(NULL AS BIGINT) AS previous_revision,
 CAST('migration' AS VARCHAR(160)) AS change_actor, CAST('BASELINE' AS VARCHAR(40)) AS change_operation,
 CAST('Existing state at retention migration; earlier snapshots not inferred' AS VARCHAR(1000)) AS change_reason,
 CURRENT_TIMESTAMP AS changed_at FROM audit_evidence WHERE 1=0;;

ALTER TABLE audit_evidence_versions ADD PRIMARY KEY(event_key,retention_revision);;

ALTER TABLE audit_evidence_versions ALTER COLUMN archive_state SET NOT NULL;;

ALTER TABLE audit_evidence_versions ADD FOREIGN KEY(event_key) REFERENCES audit_evidence(event_key);;

ALTER TABLE audit_evidence_versions ADD FOREIGN KEY(event_key,previous_revision) REFERENCES audit_evidence_versions(event_key,retention_revision);;

ALTER TABLE audit_evidence_versions ADD CHECK(retention_revision>0 AND (previous_revision IS NULL OR previous_revision=retention_revision-1));;

INSERT INTO audit_evidence_versions SELECT event_key, archive_cid, archive_state, chain_event_id, last_error,1,NULL,'migration','BASELINE','Existing state at retention migration; earlier snapshots not inferred',CURRENT_TIMESTAMP FROM audit_evidence;;

CREATE TABLE audit_evidence_head AS SELECT event_key,CAST(1 AS BIGINT) AS retention_revision,CAST(0 AS BIGINT) AS lock_epoch FROM audit_evidence;;

ALTER TABLE audit_evidence_head ADD PRIMARY KEY(event_key);;

ALTER TABLE audit_evidence_head ADD FOREIGN KEY(event_key,retention_revision) REFERENCES audit_evidence_versions(event_key,retention_revision);;

CREATE TABLE audit_evidence_runtime AS SELECT event_key, attempts, next_at, lease_until, lease_token FROM audit_evidence;;

ALTER TABLE audit_evidence_runtime ADD PRIMARY KEY(event_key);;

ALTER TABLE audit_evidence_runtime ADD FOREIGN KEY(event_key) REFERENCES audit_evidence(event_key);;

CREATE INDEX audit_evidence_runtime_due ON audit_evidence_runtime(next_at);;

CREATE VIEW audit_evidence_read AS SELECT b.event_key, b.source_id, b.event_id, b.domain_id, b.zone_id, b.app_code, b.payload_hash, b.key_id, b.ciphertext, b.ciphertext_hash, v.archive_cid, v.archive_state, v.chain_event_id, b.generic_source_id, b.occurred_at, r.attempts, r.next_at, r.lease_until, r.lease_token, v.last_error, b.created_at,v.retention_revision,
 CAST(NULL AS VARCHAR(160)) AS change_actor, CAST(NULL AS VARCHAR(40)) AS change_operation, CAST(NULL AS VARCHAR(1000)) AS change_reason
 FROM audit_evidence b JOIN audit_evidence_head h ON h.event_key=b.event_key JOIN audit_evidence_versions v ON v.event_key=h.event_key AND v.retention_revision=h.retention_revision JOIN audit_evidence_runtime r ON r.event_key=b.event_key;;

CREATE FUNCTION audit_evidence_seed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 BEGIN INSERT INTO audit_evidence_versions VALUES(NEW.event_key, NEW.archive_cid, NEW.archive_state, NEW.chain_event_id, NEW.last_error,1,NULL,COALESCE(NEW.accepted_actor,'system:worker'),COALESCE(NEW.accepted_operation,'CREATE'),COALESCE(NEW.accepted_reason,'Initial accepted state'),CURRENT_TIMESTAMP);
INSERT INTO audit_evidence_head(event_key,retention_revision,lock_epoch) VALUES(NEW.event_key,1,0);
INSERT INTO audit_evidence_runtime VALUES(NEW.event_key, NEW.attempts, NEW.next_at, NEW.lease_until, NEW.lease_token);
 RETURN NEW; END $$;;

CREATE TRIGGER audit_evidence_seed AFTER INSERT ON audit_evidence FOR EACH ROW EXECUTE PROCEDURE audit_evidence_seed();;

CREATE FUNCTION audit_evidence_write() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 DECLARE current_row audit_evidence_head%ROWTYPE; running audit_evidence_runtime%ROWTYPE;
 BEGIN IF NEW.event_key IS DISTINCT FROM OLD.event_key OR NEW.source_id IS DISTINCT FROM OLD.source_id OR NEW.event_id IS DISTINCT FROM OLD.event_id OR NEW.domain_id IS DISTINCT FROM OLD.domain_id OR NEW.zone_id IS DISTINCT FROM OLD.zone_id OR NEW.app_code IS DISTINCT FROM OLD.app_code OR NEW.payload_hash IS DISTINCT FROM OLD.payload_hash OR NEW.key_id IS DISTINCT FROM OLD.key_id OR NEW.ciphertext IS DISTINCT FROM OLD.ciphertext OR NEW.ciphertext_hash IS DISTINCT FROM OLD.ciphertext_hash OR NEW.generic_source_id IS DISTINCT FROM OLD.generic_source_id OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN RAISE EXCEPTION 'Immutable business payload cannot be changed'; END IF;
IF OLD.archive_cid IS NOT NULL AND NEW.archive_cid IS DISTINCT FROM OLD.archive_cid THEN RAISE EXCEPTION 'Accepted archive_cid cannot be replaced'; END IF;
IF OLD.chain_event_id IS NOT NULL AND NEW.chain_event_id IS DISTINCT FROM OLD.chain_event_id THEN RAISE EXCEPTION 'Accepted chain_event_id cannot be replaced'; END IF;
SELECT * INTO current_row FROM audit_evidence_head WHERE event_key=OLD.event_key FOR UPDATE;
IF current_row.retention_revision<>OLD.retention_revision THEN RAISE EXCEPTION 'Current version changed; read and retry' USING ERRCODE='40001'; END IF;
SELECT * INTO running FROM audit_evidence_runtime WHERE event_key=OLD.event_key;
IF NEW.attempts IS DISTINCT FROM OLD.attempts AND running.attempts IS DISTINCT FROM OLD.attempts THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF NEW.next_at IS DISTINCT FROM OLD.next_at AND running.next_at IS DISTINCT FROM OLD.next_at THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF NEW.lease_until IS DISTINCT FROM OLD.lease_until AND running.lease_until IS DISTINCT FROM OLD.lease_until THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF NEW.lease_token IS DISTINCT FROM OLD.lease_token AND running.lease_token IS DISTINCT FROM OLD.lease_token THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF running.lease_token IS DISTINCT FROM OLD.lease_token THEN RAISE EXCEPTION 'Worker ownership changed' USING ERRCODE='40001'; END IF;
IF OLD.lease_token IS NOT NULL AND running.lease_token=OLD.lease_token AND OLD.lease_until>statement_timestamp() AND running.lease_until<=clock_timestamp() THEN RAISE EXCEPTION 'Worker lease expired' USING ERRCODE='40001'; END IF;
IF NEW.archive_cid IS DISTINCT FROM OLD.archive_cid OR NEW.archive_state IS DISTINCT FROM OLD.archive_state OR NEW.chain_event_id IS DISTINCT FROM OLD.chain_event_id OR NEW.last_error IS DISTINCT FROM OLD.last_error OR NEW.change_operation IS NOT NULL THEN
INSERT INTO audit_evidence_versions VALUES(NEW.event_key, NEW.archive_cid, NEW.archive_state, NEW.chain_event_id, NEW.last_error,OLD.retention_revision+1,OLD.retention_revision,COALESCE(NEW.change_actor,'system:worker'),COALESCE(NEW.change_operation,'PROCESS'),COALESCE(NEW.change_reason,'Processing state transition'),CURRENT_TIMESTAMP);
UPDATE audit_evidence_head SET retention_revision=OLD.retention_revision+1 WHERE event_key=OLD.event_key;
END IF;
UPDATE audit_evidence_runtime SET attempts=CASE WHEN NEW.attempts IS DISTINCT FROM OLD.attempts THEN NEW.attempts ELSE running.attempts END, next_at=CASE WHEN NEW.next_at IS DISTINCT FROM OLD.next_at THEN NEW.next_at ELSE running.next_at END, lease_until=CASE WHEN NEW.lease_until IS DISTINCT FROM OLD.lease_until THEN NEW.lease_until ELSE running.lease_until END, lease_token=CASE WHEN NEW.lease_token IS DISTINCT FROM OLD.lease_token THEN NEW.lease_token ELSE running.lease_token END WHERE event_key=OLD.event_key;
RETURN NEW; END $$;;

CREATE TRIGGER audit_evidence_write INSTEAD OF UPDATE ON audit_evidence_read FOR EACH ROW EXECUTE PROCEDURE audit_evidence_write();;

REVOKE ALL ON FUNCTION audit_evidence_seed() FROM PUBLIC;;

REVOKE ALL ON FUNCTION audit_evidence_write() FROM PUBLIC;;

ALTER TABLE audit_delivery ADD COLUMN accepted_actor VARCHAR(160);;

ALTER TABLE audit_delivery ADD COLUMN accepted_operation VARCHAR(40);;

ALTER TABLE audit_delivery ADD COLUMN accepted_reason VARCHAR(1000);;

CREATE TABLE audit_delivery_versions AS SELECT id, event_json, state, last_error, CAST(1 AS BIGINT) AS retention_revision, CAST(NULL AS BIGINT) AS previous_revision,
 CAST('migration' AS VARCHAR(160)) AS change_actor, CAST('BASELINE' AS VARCHAR(40)) AS change_operation,
 CAST('Existing state at retention migration; earlier snapshots not inferred' AS VARCHAR(1000)) AS change_reason,
 CURRENT_TIMESTAMP AS changed_at FROM audit_delivery WHERE 1=0;;

ALTER TABLE audit_delivery_versions ADD PRIMARY KEY(id,retention_revision);;

ALTER TABLE audit_delivery_versions ALTER COLUMN state SET NOT NULL;;

ALTER TABLE audit_delivery_versions ADD FOREIGN KEY(id) REFERENCES audit_delivery(id);;

ALTER TABLE audit_delivery_versions ADD FOREIGN KEY(id,previous_revision) REFERENCES audit_delivery_versions(id,retention_revision);;

ALTER TABLE audit_delivery_versions ADD CHECK(retention_revision>0 AND (previous_revision IS NULL OR previous_revision=retention_revision-1));;

INSERT INTO audit_delivery_versions SELECT id, event_json, state, last_error,1,NULL,'migration','BASELINE','Existing state at retention migration; earlier snapshots not inferred',CURRENT_TIMESTAMP FROM audit_delivery;;

CREATE TABLE audit_delivery_head AS SELECT id,CAST(1 AS BIGINT) AS retention_revision,CAST(0 AS BIGINT) AS lock_epoch FROM audit_delivery;;

ALTER TABLE audit_delivery_head ADD PRIMARY KEY(id);;

ALTER TABLE audit_delivery_head ADD FOREIGN KEY(id,retention_revision) REFERENCES audit_delivery_versions(id,retention_revision);;

CREATE TABLE audit_delivery_runtime AS SELECT id, attempts, next_at, lease_token, lease_until FROM audit_delivery;;

ALTER TABLE audit_delivery_runtime ADD PRIMARY KEY(id);;

ALTER TABLE audit_delivery_runtime ADD FOREIGN KEY(id) REFERENCES audit_delivery(id);;

CREATE INDEX audit_delivery_runtime_due ON audit_delivery_runtime(next_at);;

CREATE VIEW audit_delivery_read AS SELECT b.id, b.org_id, b.actor, b.action, b.object_id, b.outcome, b.source_kind, b.menu_id, b.method, b.path, b.summary, b.trace_id, b.occurred_at, v.event_json, v.state, r.attempts, r.next_at, v.last_error, b.created_at, r.lease_token, r.lease_until,v.retention_revision,
 CAST(NULL AS VARCHAR(160)) AS change_actor, CAST(NULL AS VARCHAR(40)) AS change_operation, CAST(NULL AS VARCHAR(1000)) AS change_reason
 FROM audit_delivery b JOIN audit_delivery_head h ON h.id=b.id JOIN audit_delivery_versions v ON v.id=h.id AND v.retention_revision=h.retention_revision JOIN audit_delivery_runtime r ON r.id=b.id;;

CREATE FUNCTION audit_delivery_seed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 BEGIN INSERT INTO audit_delivery_versions VALUES(NEW.id, NEW.event_json, NEW.state, NEW.last_error,1,NULL,COALESCE(NEW.accepted_actor,NEW.actor),COALESCE(NEW.accepted_operation,'CREATE'),COALESCE(NEW.accepted_reason,'Initial accepted state'),CURRENT_TIMESTAMP);
INSERT INTO audit_delivery_head(id,retention_revision,lock_epoch) VALUES(NEW.id,1,0);
INSERT INTO audit_delivery_runtime VALUES(NEW.id, NEW.attempts, NEW.next_at, NEW.lease_token, NEW.lease_until);
 RETURN NEW; END $$;;

CREATE TRIGGER audit_delivery_seed AFTER INSERT ON audit_delivery FOR EACH ROW EXECUTE PROCEDURE audit_delivery_seed();;

CREATE FUNCTION audit_delivery_write() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 DECLARE current_row audit_delivery_head%ROWTYPE; running audit_delivery_runtime%ROWTYPE;
 BEGIN IF NEW.id IS DISTINCT FROM OLD.id OR NEW.org_id IS DISTINCT FROM OLD.org_id OR NEW.actor IS DISTINCT FROM OLD.actor OR NEW.action IS DISTINCT FROM OLD.action OR NEW.object_id IS DISTINCT FROM OLD.object_id OR NEW.outcome IS DISTINCT FROM OLD.outcome OR NEW.source_kind IS DISTINCT FROM OLD.source_kind OR NEW.menu_id IS DISTINCT FROM OLD.menu_id OR NEW.method IS DISTINCT FROM OLD.method OR NEW.path IS DISTINCT FROM OLD.path OR NEW.summary IS DISTINCT FROM OLD.summary OR NEW.trace_id IS DISTINCT FROM OLD.trace_id OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN RAISE EXCEPTION 'Immutable business payload cannot be changed'; END IF;
IF OLD.event_json IS NOT NULL AND NEW.event_json IS DISTINCT FROM OLD.event_json THEN RAISE EXCEPTION 'Accepted event_json cannot be replaced'; END IF;
SELECT * INTO current_row FROM audit_delivery_head WHERE id=OLD.id FOR UPDATE;
IF current_row.retention_revision<>OLD.retention_revision THEN RAISE EXCEPTION 'Current version changed; read and retry' USING ERRCODE='40001'; END IF;
SELECT * INTO running FROM audit_delivery_runtime WHERE id=OLD.id;
IF NEW.attempts IS DISTINCT FROM OLD.attempts AND running.attempts IS DISTINCT FROM OLD.attempts THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF NEW.next_at IS DISTINCT FROM OLD.next_at AND running.next_at IS DISTINCT FROM OLD.next_at THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF NEW.lease_token IS DISTINCT FROM OLD.lease_token AND running.lease_token IS DISTINCT FROM OLD.lease_token THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF NEW.lease_until IS DISTINCT FROM OLD.lease_until AND running.lease_until IS DISTINCT FROM OLD.lease_until THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF running.lease_token IS DISTINCT FROM OLD.lease_token THEN RAISE EXCEPTION 'Worker ownership changed' USING ERRCODE='40001'; END IF;
IF OLD.lease_token IS NOT NULL AND running.lease_token=OLD.lease_token AND OLD.lease_until>statement_timestamp() AND running.lease_until<=clock_timestamp() THEN RAISE EXCEPTION 'Worker lease expired' USING ERRCODE='40001'; END IF;
IF NEW.event_json IS DISTINCT FROM OLD.event_json OR NEW.state IS DISTINCT FROM OLD.state OR NEW.last_error IS DISTINCT FROM OLD.last_error OR NEW.change_operation IS NOT NULL THEN
INSERT INTO audit_delivery_versions VALUES(NEW.id, NEW.event_json, NEW.state, NEW.last_error,OLD.retention_revision+1,OLD.retention_revision,COALESCE(NEW.change_actor,'system:worker'),COALESCE(NEW.change_operation,'PROCESS'),COALESCE(NEW.change_reason,'Processing state transition'),CURRENT_TIMESTAMP);
UPDATE audit_delivery_head SET retention_revision=OLD.retention_revision+1 WHERE id=OLD.id;
END IF;
UPDATE audit_delivery_runtime SET attempts=CASE WHEN NEW.attempts IS DISTINCT FROM OLD.attempts THEN NEW.attempts ELSE running.attempts END, next_at=CASE WHEN NEW.next_at IS DISTINCT FROM OLD.next_at THEN NEW.next_at ELSE running.next_at END, lease_token=CASE WHEN NEW.lease_token IS DISTINCT FROM OLD.lease_token THEN NEW.lease_token ELSE running.lease_token END, lease_until=CASE WHEN NEW.lease_until IS DISTINCT FROM OLD.lease_until THEN NEW.lease_until ELSE running.lease_until END WHERE id=OLD.id;
RETURN NEW; END $$;;

CREATE TRIGGER audit_delivery_write INSTEAD OF UPDATE ON audit_delivery_read FOR EACH ROW EXECUTE PROCEDURE audit_delivery_write();;

REVOKE ALL ON FUNCTION audit_delivery_seed() FROM PUBLIC;;

REVOKE ALL ON FUNCTION audit_delivery_write() FROM PUBLIC;;

ALTER TABLE audit_page_action ADD COLUMN accepted_actor VARCHAR(160);;

ALTER TABLE audit_page_action ADD COLUMN accepted_operation VARCHAR(40);;

ALTER TABLE audit_page_action ADD COLUMN accepted_reason VARCHAR(1000);;

CREATE TABLE audit_page_action_versions AS SELECT id, result_json, CAST(1 AS BIGINT) AS retention_revision, CAST(NULL AS BIGINT) AS previous_revision,
 CAST('migration' AS VARCHAR(160)) AS change_actor, CAST('BASELINE' AS VARCHAR(40)) AS change_operation,
 CAST('Existing state at retention migration; earlier snapshots not inferred' AS VARCHAR(1000)) AS change_reason,
 CURRENT_TIMESTAMP AS changed_at FROM audit_page_action WHERE 1=0;;

ALTER TABLE audit_page_action_versions ADD PRIMARY KEY(id,retention_revision);;

ALTER TABLE audit_page_action_versions ADD FOREIGN KEY(id) REFERENCES audit_page_action(id);;

ALTER TABLE audit_page_action_versions ADD FOREIGN KEY(id,previous_revision) REFERENCES audit_page_action_versions(id,retention_revision);;

ALTER TABLE audit_page_action_versions ADD CHECK(retention_revision>0 AND (previous_revision IS NULL OR previous_revision=retention_revision-1));;

INSERT INTO audit_page_action_versions SELECT id, result_json,1,NULL,'migration','BASELINE','Existing state at retention migration; earlier snapshots not inferred',CURRENT_TIMESTAMP FROM audit_page_action;;

CREATE TABLE audit_page_action_head AS SELECT id,CAST(1 AS BIGINT) AS retention_revision,CAST(0 AS BIGINT) AS lock_epoch FROM audit_page_action;;

ALTER TABLE audit_page_action_head ADD PRIMARY KEY(id);;

ALTER TABLE audit_page_action_head ADD FOREIGN KEY(id,retention_revision) REFERENCES audit_page_action_versions(id,retention_revision);;

CREATE VIEW audit_page_action_read AS SELECT b.id, b.org_id, b.actor, b.request_json, v.result_json, b.created_at, b.identity_mode,v.retention_revision,
 CAST(NULL AS VARCHAR(160)) AS change_actor, CAST(NULL AS VARCHAR(40)) AS change_operation, CAST(NULL AS VARCHAR(1000)) AS change_reason
 FROM audit_page_action b JOIN audit_page_action_head h ON h.id=b.id JOIN audit_page_action_versions v ON v.id=h.id AND v.retention_revision=h.retention_revision;;

CREATE FUNCTION audit_page_action_seed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 BEGIN INSERT INTO audit_page_action_versions VALUES(NEW.id, NEW.result_json,1,NULL,COALESCE(NEW.accepted_actor,NEW.actor),COALESCE(NEW.accepted_operation,'CREATE'),COALESCE(NEW.accepted_reason,'Initial accepted state'),CURRENT_TIMESTAMP);
INSERT INTO audit_page_action_head(id,retention_revision,lock_epoch) VALUES(NEW.id,1,0);
 RETURN NEW; END $$;;

CREATE TRIGGER audit_page_action_seed AFTER INSERT ON audit_page_action FOR EACH ROW EXECUTE PROCEDURE audit_page_action_seed();;

CREATE FUNCTION audit_page_action_write() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 DECLARE current_row audit_page_action_head%ROWTYPE; 
 BEGIN IF NEW.id IS DISTINCT FROM OLD.id OR NEW.org_id IS DISTINCT FROM OLD.org_id OR NEW.actor IS DISTINCT FROM OLD.actor OR NEW.request_json IS DISTINCT FROM OLD.request_json OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.identity_mode IS DISTINCT FROM OLD.identity_mode THEN RAISE EXCEPTION 'Immutable business payload cannot be changed'; END IF;
IF OLD.result_json IS NOT NULL AND NEW.result_json IS DISTINCT FROM OLD.result_json THEN RAISE EXCEPTION 'Accepted result_json cannot be replaced'; END IF;
SELECT * INTO current_row FROM audit_page_action_head WHERE id=OLD.id FOR UPDATE;
IF current_row.retention_revision<>OLD.retention_revision THEN RAISE EXCEPTION 'Current version changed; read and retry' USING ERRCODE='40001'; END IF;
IF NEW.result_json IS DISTINCT FROM OLD.result_json OR NEW.change_operation IS NOT NULL THEN
INSERT INTO audit_page_action_versions VALUES(NEW.id, NEW.result_json,OLD.retention_revision+1,OLD.retention_revision,COALESCE(NEW.change_actor,'system:worker'),COALESCE(NEW.change_operation,'PROCESS'),COALESCE(NEW.change_reason,'Processing state transition'),CURRENT_TIMESTAMP);
UPDATE audit_page_action_head SET retention_revision=OLD.retention_revision+1 WHERE id=OLD.id;
END IF;
RETURN NEW; END $$;;

CREATE TRIGGER audit_page_action_write INSTEAD OF UPDATE ON audit_page_action_read FOR EACH ROW EXECUTE PROCEDURE audit_page_action_write();;

REVOKE ALL ON FUNCTION audit_page_action_seed() FROM PUBLIC;;

REVOKE ALL ON FUNCTION audit_page_action_write() FROM PUBLIC;;

ALTER TABLE signing_attempts ADD COLUMN accepted_actor VARCHAR(160);;

ALTER TABLE signing_attempts ADD COLUMN accepted_operation VARCHAR(40);;

ALTER TABLE signing_attempts ADD COLUMN accepted_reason VARCHAR(1000);;

CREATE TABLE signing_attempts_versions AS SELECT tx_id, state, ledger_identity, block_number, last_error, resolved_at, CAST(1 AS BIGINT) AS retention_revision, CAST(NULL AS BIGINT) AS previous_revision,
 CAST('migration' AS VARCHAR(160)) AS change_actor, CAST('BASELINE' AS VARCHAR(40)) AS change_operation,
 CAST('Existing state at retention migration; earlier snapshots not inferred' AS VARCHAR(1000)) AS change_reason,
 CURRENT_TIMESTAMP AS changed_at FROM signing_attempts WHERE 1=0;;

ALTER TABLE signing_attempts_versions ADD PRIMARY KEY(tx_id,retention_revision);;

ALTER TABLE signing_attempts_versions ALTER COLUMN state SET NOT NULL;;

ALTER TABLE signing_attempts_versions ADD FOREIGN KEY(tx_id) REFERENCES signing_attempts(tx_id);;

ALTER TABLE signing_attempts_versions ADD FOREIGN KEY(tx_id,previous_revision) REFERENCES signing_attempts_versions(tx_id,retention_revision);;

ALTER TABLE signing_attempts_versions ADD CHECK(retention_revision>0 AND (previous_revision IS NULL OR previous_revision=retention_revision-1));;

INSERT INTO signing_attempts_versions SELECT tx_id, state, ledger_identity, block_number, last_error, resolved_at,1,NULL,'migration','BASELINE','Existing state at retention migration; earlier snapshots not inferred',CURRENT_TIMESTAMP FROM signing_attempts;;

CREATE TABLE signing_attempts_head AS SELECT tx_id,CAST(1 AS BIGINT) AS retention_revision,CAST(0 AS BIGINT) AS lock_epoch FROM signing_attempts;;

ALTER TABLE signing_attempts_head ADD PRIMARY KEY(tx_id);;

ALTER TABLE signing_attempts_head ADD FOREIGN KEY(tx_id,retention_revision) REFERENCES signing_attempts_versions(tx_id,retention_revision);;

CREATE VIEW signing_attempts_read AS SELECT b.tx_id, b.event_id, b.wallet_id, b.fingerprint, v.state, b.created_at, b.context_json, b.context_sha256, v.ledger_identity, v.block_number, v.last_error, v.resolved_at,v.retention_revision,
 CAST(NULL AS VARCHAR(160)) AS change_actor, CAST(NULL AS VARCHAR(40)) AS change_operation, CAST(NULL AS VARCHAR(1000)) AS change_reason
 FROM signing_attempts b JOIN signing_attempts_head h ON h.tx_id=b.tx_id JOIN signing_attempts_versions v ON v.tx_id=h.tx_id AND v.retention_revision=h.retention_revision;;

CREATE FUNCTION signing_attempts_seed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 BEGIN INSERT INTO signing_attempts_versions VALUES(NEW.tx_id, NEW.state, NEW.ledger_identity, NEW.block_number, NEW.last_error, NEW.resolved_at,1,NULL,COALESCE(NEW.accepted_actor,'system:worker'),COALESCE(NEW.accepted_operation,'CREATE'),COALESCE(NEW.accepted_reason,'Initial accepted state'),CURRENT_TIMESTAMP);
INSERT INTO signing_attempts_head(tx_id,retention_revision,lock_epoch) VALUES(NEW.tx_id,1,0);
 RETURN NEW; END $$;;

CREATE TRIGGER signing_attempts_seed AFTER INSERT ON signing_attempts FOR EACH ROW EXECUTE PROCEDURE signing_attempts_seed();;

CREATE FUNCTION signing_attempts_write() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 DECLARE current_row signing_attempts_head%ROWTYPE; 
 BEGIN IF NEW.tx_id IS DISTINCT FROM OLD.tx_id OR NEW.event_id IS DISTINCT FROM OLD.event_id OR NEW.wallet_id IS DISTINCT FROM OLD.wallet_id OR NEW.fingerprint IS DISTINCT FROM OLD.fingerprint OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.context_json IS DISTINCT FROM OLD.context_json OR NEW.context_sha256 IS DISTINCT FROM OLD.context_sha256 THEN RAISE EXCEPTION 'Immutable business payload cannot be changed'; END IF;
SELECT * INTO current_row FROM signing_attempts_head WHERE tx_id=OLD.tx_id FOR UPDATE;
IF current_row.retention_revision<>OLD.retention_revision THEN RAISE EXCEPTION 'Current version changed; read and retry' USING ERRCODE='40001'; END IF;
IF NEW.state IS DISTINCT FROM OLD.state OR NEW.ledger_identity IS DISTINCT FROM OLD.ledger_identity OR NEW.block_number IS DISTINCT FROM OLD.block_number OR NEW.last_error IS DISTINCT FROM OLD.last_error OR NEW.resolved_at IS DISTINCT FROM OLD.resolved_at OR NEW.change_operation IS NOT NULL THEN
INSERT INTO signing_attempts_versions VALUES(NEW.tx_id, NEW.state, NEW.ledger_identity, NEW.block_number, NEW.last_error, NEW.resolved_at,OLD.retention_revision+1,OLD.retention_revision,COALESCE(NEW.change_actor,'system:worker'),COALESCE(NEW.change_operation,'PROCESS'),COALESCE(NEW.change_reason,'Processing state transition'),CURRENT_TIMESTAMP);
UPDATE signing_attempts_head SET retention_revision=OLD.retention_revision+1 WHERE tx_id=OLD.tx_id;
END IF;
RETURN NEW; END $$;;

CREATE TRIGGER signing_attempts_write INSTEAD OF UPDATE ON signing_attempts_read FOR EACH ROW EXECUTE PROCEDURE signing_attempts_write();;

REVOKE ALL ON FUNCTION signing_attempts_seed() FROM PUBLIC;;

REVOKE ALL ON FUNCTION signing_attempts_write() FROM PUBLIC;;

ALTER TABLE tasks ADD COLUMN accepted_actor VARCHAR(160);;

ALTER TABLE tasks ADD COLUMN accepted_operation VARCHAR(40);;

ALTER TABLE tasks ADD COLUMN accepted_reason VARCHAR(1000);;

CREATE TABLE tasks_versions AS SELECT event_id, state, last_error, CAST(1 AS BIGINT) AS retention_revision, CAST(NULL AS BIGINT) AS previous_revision,
 CAST('migration' AS VARCHAR(160)) AS change_actor, CAST('BASELINE' AS VARCHAR(40)) AS change_operation,
 CAST('Existing state at retention migration; earlier snapshots not inferred' AS VARCHAR(1000)) AS change_reason,
 CURRENT_TIMESTAMP AS changed_at FROM tasks WHERE 1=0;;

ALTER TABLE tasks_versions ADD PRIMARY KEY(event_id,retention_revision);;

ALTER TABLE tasks_versions ALTER COLUMN state SET NOT NULL;;

ALTER TABLE tasks_versions ADD FOREIGN KEY(event_id) REFERENCES tasks(event_id);;

ALTER TABLE tasks_versions ADD FOREIGN KEY(event_id,previous_revision) REFERENCES tasks_versions(event_id,retention_revision);;

ALTER TABLE tasks_versions ADD CHECK(retention_revision>0 AND (previous_revision IS NULL OR previous_revision=retention_revision-1));;

INSERT INTO tasks_versions SELECT event_id, state, last_error,1,NULL,'migration','BASELINE','Existing state at retention migration; earlier snapshots not inferred',CURRENT_TIMESTAMP FROM tasks;;

CREATE TABLE tasks_head AS SELECT event_id,CAST(1 AS BIGINT) AS retention_revision,CAST(0 AS BIGINT) AS lock_epoch FROM tasks;;

ALTER TABLE tasks_head ADD PRIMARY KEY(event_id);;

ALTER TABLE tasks_head ADD FOREIGN KEY(event_id,retention_revision) REFERENCES tasks_versions(event_id,retention_revision);;

CREATE TABLE tasks_runtime AS SELECT event_id, attempts, next_at, lease_token, lease_until, updated_at FROM tasks;;

ALTER TABLE tasks_runtime ADD PRIMARY KEY(event_id);;

ALTER TABLE tasks_runtime ADD FOREIGN KEY(event_id) REFERENCES tasks(event_id);;

CREATE INDEX tasks_runtime_due ON tasks_runtime(next_at);;

CREATE VIEW tasks_read AS SELECT b.event_id, v.state, r.attempts, r.next_at, r.lease_token, r.lease_until, r.updated_at, v.last_error,v.retention_revision,
 CAST(NULL AS VARCHAR(160)) AS change_actor, CAST(NULL AS VARCHAR(40)) AS change_operation, CAST(NULL AS VARCHAR(1000)) AS change_reason
 FROM tasks b JOIN tasks_head h ON h.event_id=b.event_id JOIN tasks_versions v ON v.event_id=h.event_id AND v.retention_revision=h.retention_revision JOIN tasks_runtime r ON r.event_id=b.event_id;;

CREATE FUNCTION tasks_seed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 BEGIN INSERT INTO tasks_versions VALUES(NEW.event_id, NEW.state, NEW.last_error,1,NULL,COALESCE(NEW.accepted_actor,'system:worker'),COALESCE(NEW.accepted_operation,'CREATE'),COALESCE(NEW.accepted_reason,'Initial accepted state'),CURRENT_TIMESTAMP);
INSERT INTO tasks_head(event_id,retention_revision,lock_epoch) VALUES(NEW.event_id,1,0);
INSERT INTO tasks_runtime VALUES(NEW.event_id, NEW.attempts, NEW.next_at, NEW.lease_token, NEW.lease_until, NEW.updated_at);
 RETURN NEW; END $$;;

CREATE TRIGGER tasks_seed AFTER INSERT ON tasks FOR EACH ROW EXECUTE PROCEDURE tasks_seed();;

CREATE FUNCTION tasks_write() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 DECLARE current_row tasks_head%ROWTYPE; running tasks_runtime%ROWTYPE;
 BEGIN IF NEW.event_id IS DISTINCT FROM OLD.event_id THEN RAISE EXCEPTION 'Immutable business payload cannot be changed'; END IF;
SELECT * INTO current_row FROM tasks_head WHERE event_id=OLD.event_id FOR UPDATE;
IF current_row.retention_revision<>OLD.retention_revision THEN RAISE EXCEPTION 'Current version changed; read and retry' USING ERRCODE='40001'; END IF;
SELECT * INTO running FROM tasks_runtime WHERE event_id=OLD.event_id;
IF NEW.attempts IS DISTINCT FROM OLD.attempts AND running.attempts IS DISTINCT FROM OLD.attempts THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF NEW.next_at IS DISTINCT FROM OLD.next_at AND running.next_at IS DISTINCT FROM OLD.next_at THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF NEW.lease_token IS DISTINCT FROM OLD.lease_token AND running.lease_token IS DISTINCT FROM OLD.lease_token THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF NEW.lease_until IS DISTINCT FROM OLD.lease_until AND running.lease_until IS DISTINCT FROM OLD.lease_until THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF NEW.updated_at IS DISTINCT FROM OLD.updated_at AND running.updated_at IS DISTINCT FROM OLD.updated_at THEN RAISE EXCEPTION 'Runtime ownership changed' USING ERRCODE='40001'; END IF;
IF running.lease_token IS DISTINCT FROM OLD.lease_token THEN RAISE EXCEPTION 'Worker ownership changed' USING ERRCODE='40001'; END IF;
IF OLD.lease_token IS NOT NULL AND running.lease_token=OLD.lease_token AND OLD.lease_until>statement_timestamp() AND running.lease_until<=clock_timestamp() THEN RAISE EXCEPTION 'Worker lease expired' USING ERRCODE='40001'; END IF;
IF NEW.state IS DISTINCT FROM OLD.state OR NEW.last_error IS DISTINCT FROM OLD.last_error OR NEW.change_operation IS NOT NULL THEN
INSERT INTO tasks_versions VALUES(NEW.event_id, NEW.state, NEW.last_error,OLD.retention_revision+1,OLD.retention_revision,COALESCE(NEW.change_actor,'system:worker'),COALESCE(NEW.change_operation,'PROCESS'),COALESCE(NEW.change_reason,'Processing state transition'),CURRENT_TIMESTAMP);
UPDATE tasks_head SET retention_revision=OLD.retention_revision+1 WHERE event_id=OLD.event_id;
END IF;
UPDATE tasks_runtime SET attempts=CASE WHEN NEW.attempts IS DISTINCT FROM OLD.attempts THEN NEW.attempts ELSE running.attempts END, next_at=CASE WHEN NEW.next_at IS DISTINCT FROM OLD.next_at THEN NEW.next_at ELSE running.next_at END, lease_token=CASE WHEN NEW.lease_token IS DISTINCT FROM OLD.lease_token THEN NEW.lease_token ELSE running.lease_token END, lease_until=CASE WHEN NEW.lease_until IS DISTINCT FROM OLD.lease_until THEN NEW.lease_until ELSE running.lease_until END, updated_at=CASE WHEN NEW.updated_at IS DISTINCT FROM OLD.updated_at THEN NEW.updated_at ELSE running.updated_at END WHERE event_id=OLD.event_id;
RETURN NEW; END $$;;

CREATE TRIGGER tasks_write INSTEAD OF UPDATE ON tasks_read FOR EACH ROW EXECUTE PROCEDURE tasks_write();;

REVOKE ALL ON FUNCTION tasks_seed() FROM PUBLIC;;

REVOKE ALL ON FUNCTION tasks_write() FROM PUBLIC;;

ALTER TABLE wallets ADD UNIQUE(id,org_id);;

ALTER TABLE wallet_requests ADD UNIQUE(id,org_id);;

ALTER TABLE events ADD UNIQUE(id,org_id);;

ALTER TABLE event_heads ADD FOREIGN KEY(current_id,org_id) REFERENCES events(id,org_id);;

ALTER TABLE event_heads ADD FOREIGN KEY(root_id,org_id) REFERENCES events(id,org_id);;

ALTER TABLE wallet_bindings_versions ADD FOREIGN KEY(wallet_id,org_id) REFERENCES wallets(id,org_id);;

ALTER TABLE wallet_bindings_versions ADD FOREIGN KEY(change_request_id,org_id) REFERENCES wallet_requests(id,org_id);;

ALTER TABLE events_versions ADD FOREIGN KEY(wallet_id) REFERENCES wallets(id);;

ALTER TABLE audit_evidence_versions ADD FOREIGN KEY(chain_event_id) REFERENCES events(id);;

CREATE FUNCTION events_version_org() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
 BEGIN
 IF NEW.wallet_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM wallets w JOIN events b ON b.org_id=w.org_id WHERE w.id=NEW.wallet_id AND b.id=NEW.id) THEN RAISE EXCEPTION 'Cross-organization version reference'; END IF;
 RETURN NEW; END $$;;

CREATE TRIGGER events_version_org BEFORE INSERT ON events_versions FOR EACH ROW EXECUTE PROCEDURE events_version_org();;

REVOKE ALL ON FUNCTION events_version_org() FROM PUBLIC;;

DO $$ BEGIN IF EXISTS(SELECT 1 FROM events_versions v JOIN events b USING(id) JOIN wallets w ON w.id=v.wallet_id WHERE b.org_id<>w.org_id) THEN RAISE EXCEPTION 'Cross-organization baseline reference'; END IF; END $$;;
