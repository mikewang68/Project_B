ALTER TABLE events ADD COLUMN record_kind VARCHAR(20) NOT NULL DEFAULT 'LEGACY_V1';
ALTER TABLE events ADD CONSTRAINT event_kind_valid CHECK(record_kind IN ('LEGACY_V1','GENERIC_V1'));
ALTER TABLE events ADD CONSTRAINT event_batch_kind CHECK(
 (record_kind='LEGACY_V1' AND batch_id IS NOT NULL) OR
 (record_kind='GENERIC_V1' AND batch_id IS NULL AND signing_required=TRUE));
ALTER TABLE events ALTER COLUMN batch_id DROP NOT NULL;
CREATE TABLE generic_event_data (
 event_id VARCHAR(36) PRIMARY KEY REFERENCES events(id),
 original_utf8 TEXT NOT NULL, original_sha256 CHAR(64) NOT NULL,
 original_size_bytes BIGINT NOT NULL CHECK(original_size_bytes BETWEEN 1 AND 262144),
 original_cid VARCHAR(128), last_error_code VARCHAR(80)
);
CREATE TABLE event_source_routes (
 org_id VARCHAR(80) NOT NULL, source_system VARCHAR(80) NOT NULL,
 record_kind VARCHAR(20) NOT NULL, source_id VARCHAR(70),
 PRIMARY KEY(org_id,source_system),
 CHECK((record_kind='LEGACY_V1' AND source_id IS NULL) OR
       (record_kind='GENERIC_V1' AND source_id IS NOT NULL))
);
INSERT INTO event_source_routes(org_id,source_system,record_kind)
 SELECT DISTINCT org_id,source_system,'LEGACY_V1' FROM events;
