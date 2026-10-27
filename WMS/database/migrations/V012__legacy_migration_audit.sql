CREATE TABLE legacy_migration_run (
    id BIGSERIAL PRIMARY KEY,
    run_code VARCHAR(80) NOT NULL UNIQUE,
    source_name VARCHAR(128) NOT NULL,
    source_fingerprint VARCHAR(64) NOT NULL,
    target_company_code VARCHAR(64) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL,
    statistics TEXT,
    error_message VARCHAR(2000),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_legacy_migration_mode CHECK(mode IN ('PRECHECK','EXECUTE','VERIFY')),
    CONSTRAINT ck_legacy_migration_state CHECK(state IN ('RUNNING','SUCCEEDED','FAILED'))
);

CREATE INDEX idx_legacy_migration_run_time ON legacy_migration_run(started_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON legacy_migration_run TO wms_app;
GRANT USAGE, SELECT, UPDATE ON SEQUENCE legacy_migration_run_id_seq TO wms_app;
