ALTER TABLE inv_operation ADD COLUMN idempotency_key VARCHAR(128);
CREATE UNIQUE INDEX uk_inv_operation_idempotency ON inv_operation(company_id,idempotency_key);

CREATE TABLE inv_replenishment_rule (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    location_id BIGINT NOT NULL REFERENCES wms_location(id),
    good_id BIGINT NOT NULL REFERENCES wms_good(id),
    min_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    max_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    remark VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_inv_replenishment_dimension UNIQUE (warehouse_id,owner_id,location_id,good_id),
    CONSTRAINT ck_inv_replenishment_qty CHECK (min_qty >= 0 AND max_qty >= min_qty),
    CONSTRAINT ck_inv_replenishment_status CHECK (status IN ('ENABLED','DISABLED'))
);

CREATE INDEX idx_inv_replenishment_tenant ON inv_replenishment_rule(company_id,warehouse_id,owner_id);
GRANT SELECT, INSERT, UPDATE, DELETE ON inv_replenishment_rule TO wms_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO wms_app;
