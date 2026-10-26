CREATE TABLE IF NOT EXISTS wms_business_sequence (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    prefix VARCHAR(16) NOT NULL,
    period VARCHAR(8) NOT NULL,
    current_value BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wms_business_sequence UNIQUE (company_id, warehouse_id, owner_id, prefix, period)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON wms_business_sequence TO wms_app;
GRANT USAGE, SELECT, UPDATE ON SEQUENCE wms_business_sequence_id_seq TO wms_app;
