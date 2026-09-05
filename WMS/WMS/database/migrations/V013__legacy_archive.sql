CREATE TABLE legacy_archive_record (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    source_table VARCHAR(80) NOT NULL,
    source_pk VARCHAR(128) NOT NULL,
    source_json TEXT NOT NULL,
    archived_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_legacy_archive_source UNIQUE(company_id,source_table,source_pk)
);

CREATE INDEX idx_legacy_archive_table ON legacy_archive_record(company_id,source_table);

GRANT SELECT, INSERT, UPDATE, DELETE ON legacy_archive_record TO wms_app;
GRANT USAGE, SELECT, UPDATE ON SEQUENCE legacy_archive_record_id_seq TO wms_app;
