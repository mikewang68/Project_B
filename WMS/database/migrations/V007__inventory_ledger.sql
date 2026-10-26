CREATE TABLE IF NOT EXISTS inv_balance (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    location_id BIGINT NOT NULL REFERENCES wms_location(id),
    good_id BIGINT NOT NULL REFERENCES wms_good(id),
    batch_code VARCHAR(64) NOT NULL DEFAULT '',
    quality_type VARCHAR(8) NOT NULL DEFAULT 'ZP',
    supplier_code VARCHAR(64) NOT NULL DEFAULT '',
    product_date DATE,
    expire_date DATE,
    lpn VARCHAR(64) NOT NULL DEFAULT '',
    available_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    allocated_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    frozen_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_inv_balance_dimension UNIQUE (warehouse_id,owner_id,location_id,good_id,batch_code,quality_type,supplier_code,lpn),
    CONSTRAINT ck_inv_balance_quality CHECK (quality_type IN ('ZP','CC','DJ','ZT','JS','XS')),
    CONSTRAINT ck_inv_balance_available CHECK (available_qty >= 0),
    CONSTRAINT ck_inv_balance_allocated CHECK (allocated_qty >= 0),
    CONSTRAINT ck_inv_balance_frozen CHECK (frozen_qty >= 0)
);

CREATE TABLE IF NOT EXISTS inv_transaction (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    balance_id BIGINT REFERENCES inv_balance(id),
    good_id BIGINT NOT NULL REFERENCES wms_good(id),
    location_id BIGINT NOT NULL REFERENCES wms_location(id),
    transaction_type VARCHAR(24) NOT NULL,
    operation_code VARCHAR(64) NOT NULL,
    quantity_before NUMERIC(18,3) NOT NULL,
    quantity_change NUMERIC(18,3) NOT NULL,
    quantity_after NUMERIC(18,3) NOT NULL,
    available_before NUMERIC(18,3) NOT NULL,
    available_change NUMERIC(18,3) NOT NULL,
    available_after NUMERIC(18,3) NOT NULL,
    frozen_before NUMERIC(18,3) NOT NULL,
    frozen_change NUMERIC(18,3) NOT NULL,
    frozen_after NUMERIC(18,3) NOT NULL,
    related_location_id BIGINT REFERENCES wms_location(id),
    idempotency_key VARCHAR(128) NOT NULL,
    operator_user_id BIGINT NOT NULL REFERENCES auth_user(id),
    remark VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_inv_transaction_idempotency UNIQUE (company_id,idempotency_key),
    CONSTRAINT ck_inv_transaction_type CHECK (transaction_type IN ('ADJUST','MOVE_OUT','MOVE_IN','FREEZE','UNFREEZE','COUNT'))
);

CREATE TABLE IF NOT EXISTS inv_operation (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    operation_code VARCHAR(64) NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'EXECUTED',
    operator_user_id BIGINT NOT NULL REFERENCES auth_user(id),
    remark VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_inv_operation_code UNIQUE (company_id,operation_code),
    CONSTRAINT ck_inv_operation_type CHECK (operation_type IN ('ADJUST','MOVE','FREEZE','UNFREEZE','COUNT')),
    CONSTRAINT ck_inv_operation_state CHECK (state IN ('DRAFT','EXECUTED','CANCELLED'))
);

CREATE TABLE IF NOT EXISTS inv_operation_line (
    id BIGSERIAL PRIMARY KEY,
    operation_id BIGINT NOT NULL REFERENCES inv_operation(id) ON DELETE CASCADE,
    good_id BIGINT NOT NULL REFERENCES wms_good(id),
    source_location_id BIGINT REFERENCES wms_location(id),
    destination_location_id BIGINT REFERENCES wms_location(id),
    balance_id BIGINT REFERENCES inv_balance(id),
    system_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    requested_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    actual_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    difference_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    batch_code VARCHAR(64) NOT NULL DEFAULT '',
    quality_type VARCHAR(8) NOT NULL DEFAULT 'ZP',
    remark VARCHAR(256)
);

CREATE TABLE IF NOT EXISTS inv_serial (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    balance_id BIGINT NOT NULL REFERENCES inv_balance(id),
    good_id BIGINT NOT NULL REFERENCES wms_good(id),
    location_id BIGINT NOT NULL REFERENCES wms_location(id),
    serial_code VARCHAR(128) NOT NULL,
    quantity NUMERIC(18,3) NOT NULL DEFAULT 1,
    weight_kg NUMERIC(14,4) NOT NULL DEFAULT 0,
    source VARCHAR(16) NOT NULL DEFAULT 'SYSTEM',
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    printed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_inv_serial_company_code UNIQUE (company_id,serial_code),
    CONSTRAINT ck_inv_serial_qty CHECK (quantity > 0),
    CONSTRAINT ck_inv_serial_state CHECK (state IN ('ACTIVE','OUTBOUND','VOID'))
);

CREATE INDEX IF NOT EXISTS idx_inv_balance_tenant_good ON inv_balance(company_id,warehouse_id,owner_id,good_id);
CREATE INDEX IF NOT EXISTS idx_inv_transaction_tenant_time ON inv_transaction(company_id,warehouse_id,owner_id,created_at);
CREATE INDEX IF NOT EXISTS idx_inv_serial_tenant_good ON inv_serial(company_id,warehouse_id,owner_id,good_id);

INSERT INTO auth_permission(code,name) SELECT 'inventory:write','执行库存操作'
WHERE NOT EXISTS (SELECT 1 FROM auth_permission WHERE code='inventory:write');
INSERT INTO auth_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM auth_role r CROSS JOIN auth_permission p
WHERE p.code='inventory:write'
AND NOT EXISTS (SELECT 1 FROM auth_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO wms_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO wms_app;
