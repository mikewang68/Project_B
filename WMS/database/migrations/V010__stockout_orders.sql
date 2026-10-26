ALTER TABLE inv_transaction DROP CONSTRAINT ck_inv_transaction_type;
ALTER TABLE inv_transaction ADD CONSTRAINT ck_inv_transaction_type CHECK (transaction_type IN ('ADJUST','MOVE_OUT','MOVE_IN','FREEZE','UNFREEZE','COUNT','STOCK_IN','STOCK_OUT'));
ALTER TABLE inv_operation DROP CONSTRAINT ck_inv_operation_type;
ALTER TABLE inv_operation ADD CONSTRAINT ck_inv_operation_type CHECK (operation_type IN ('ADJUST','MOVE','FREEZE','UNFREEZE','COUNT','STOCK_IN','STOCK_OUT'));

CREATE TABLE stockout_order (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    order_code VARCHAR(64) NOT NULL,
    external_order_code VARCHAR(64),
    related_order_code VARCHAR(64),
    outbound_type VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    source VARCHAR(16) NOT NULL DEFAULT 'CUSTOM',
    state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    allocation_state VARCHAR(16) NOT NULL DEFAULT 'NO',
    pick_state VARCHAR(16) NOT NULL DEFAULT 'NO',
    ship_state VARCHAR(16) NOT NULL DEFAULT 'NO',
    partner_id BIGINT REFERENCES wms_partner(id),
    planned_date DATE,
    receiver_name VARCHAR(64),
    receiver_phone VARCHAR(32),
    receiver_address VARCHAR(256),
    carrier_code VARCHAR(64),
    tracking_code VARCHAR(128),
    total_amount NUMERIC(18,4) NOT NULL DEFAULT 0,
    target_warehouse_id BIGINT REFERENCES wms_warehouse(id),
    target_owner_id BIGINT REFERENCES wms_owner(id),
    transfer_in_order_id BIGINT REFERENCES stockin_order(id),
    completed_at TIMESTAMP WITH TIME ZONE,
    created_by BIGINT NOT NULL REFERENCES auth_user(id),
    remark VARCHAR(256),
    idempotency_key VARCHAR(80) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stockout_order_code UNIQUE (company_id,order_code),
    CONSTRAINT uk_stockout_order_idempotency UNIQUE (company_id,idempotency_key),
    CONSTRAINT ck_stockout_order_type CHECK (outbound_type IN ('SALE','PRODUCE','NORMAL','RETURN','COOP','MATERIAL_PICK','FIX','SCRAP','BORROW','TRANSFER','CONSIGN','CUSTOM')),
    CONSTRAINT ck_stockout_order_source CHECK (source IN ('ERP','CUSTOM','IMPORT')),
    CONSTRAINT ck_stockout_order_state CHECK (state IN ('DRAFT','PROCESSING','COMPLETED','CANCELLED')),
    CONSTRAINT ck_stockout_alloc_state CHECK (allocation_state IN ('NO','PARTIAL','FULL')),
    CONSTRAINT ck_stockout_pick_state CHECK (pick_state IN ('NO','PARTIAL','FULL')),
    CONSTRAINT ck_stockout_ship_state CHECK (ship_state IN ('NO','PARTIAL','FULL'))
);
CREATE UNIQUE INDEX uk_stockout_external_code ON stockout_order(company_id,warehouse_id,owner_id,external_order_code);

CREATE TABLE stockout_order_line (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES stockout_order(id) ON DELETE CASCADE,
    line_no INTEGER NOT NULL,
    good_id BIGINT NOT NULL REFERENCES wms_good(id),
    planned_qty NUMERIC(18,3) NOT NULL,
    allocated_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    picked_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    shipped_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    supplier_code VARCHAR(64) NOT NULL DEFAULT '-',
    quality_type VARCHAR(8) NOT NULL DEFAULT 'ZP',
    batch_code VARCHAR(64) NOT NULL DEFAULT '-',
    unit_price NUMERIC(18,4) NOT NULL DEFAULT 0,
    remark VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stockout_line_no UNIQUE (order_id,line_no),
    CONSTRAINT ck_stockout_line_planned CHECK (planned_qty > 0),
    CONSTRAINT ck_stockout_line_quantities CHECK (allocated_qty>=0 AND picked_qty>=0 AND shipped_qty>=0 AND shipped_qty<=picked_qty AND picked_qty<=allocated_qty),
    CONSTRAINT ck_stockout_line_quality CHECK (quality_type IN ('ZP','CC','DJ','ZT','JS','XS'))
);

CREATE TABLE stockout_allocation (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    order_id BIGINT NOT NULL REFERENCES stockout_order(id),
    order_line_id BIGINT NOT NULL REFERENCES stockout_order_line(id),
    balance_id BIGINT NOT NULL REFERENCES inv_balance(id),
    good_id BIGINT NOT NULL REFERENCES wms_good(id),
    location_id BIGINT NOT NULL REFERENCES wms_location(id),
    allocated_qty NUMERIC(18,3) NOT NULL,
    picked_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    shipped_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_stockout_allocation_qty CHECK (allocated_qty>0 AND picked_qty>=0 AND shipped_qty>=0 AND shipped_qty<=picked_qty AND picked_qty<=allocated_qty),
    CONSTRAINT ck_stockout_allocation_state CHECK (state IN ('ACTIVE','CANCELLED','SHIPPED'))
);

CREATE TABLE stockout_event (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    order_id BIGINT NOT NULL REFERENCES stockout_order(id),
    order_line_id BIGINT REFERENCES stockout_order_line(id),
    allocation_id BIGINT REFERENCES stockout_allocation(id),
    event_type VARCHAR(20) NOT NULL,
    operation_code VARCHAR(64),
    quantity NUMERIC(18,3) NOT NULL DEFAULT 0,
    location_id BIGINT REFERENCES wms_location(id),
    operator_user_id BIGINT NOT NULL REFERENCES auth_user(id),
    remark VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_stockout_event_type CHECK (event_type IN ('ALLOCATE','UNALLOCATE','PICK','PACK','SHIP','CANCEL','COMPLETE','REVERSE'))
);

CREATE TABLE stockout_package (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    order_id BIGINT NOT NULL REFERENCES stockout_order(id),
    package_code VARCHAR(64) NOT NULL,
    carrier_code VARCHAR(64),
    tracking_code VARCHAR(128),
    state VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    shipped_at TIMESTAMP WITH TIME ZONE,
    created_by BIGINT NOT NULL REFERENCES auth_user(id),
    remark VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stockout_package_code UNIQUE(company_id,package_code),
    CONSTRAINT ck_stockout_package_state CHECK (state IN ('OPEN','SHIPPED','CANCELLED'))
);

CREATE TABLE stockout_package_line (
    id BIGSERIAL PRIMARY KEY,
    package_id BIGINT NOT NULL REFERENCES stockout_package(id) ON DELETE CASCADE,
    order_line_id BIGINT NOT NULL REFERENCES stockout_order_line(id),
    quantity NUMERIC(18,3) NOT NULL,
    CONSTRAINT uk_stockout_package_line UNIQUE(package_id,order_line_id),
    CONSTRAINT ck_stockout_package_line_qty CHECK(quantity>0)
);

CREATE TABLE stockout_serial_pick (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES stockout_order(id),
    order_line_id BIGINT NOT NULL REFERENCES stockout_order_line(id),
    allocation_id BIGINT NOT NULL REFERENCES stockout_allocation(id),
    serial_id BIGINT NOT NULL REFERENCES inv_serial(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stockout_serial_pick UNIQUE(order_id,serial_id)
);

CREATE TABLE stockout_wave (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    wave_code VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by BIGINT NOT NULL REFERENCES auth_user(id),
    idempotency_key VARCHAR(80) NOT NULL,
    remark VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stockout_wave_code UNIQUE(company_id,wave_code),
    CONSTRAINT uk_stockout_wave_idempotency UNIQUE(company_id,idempotency_key),
    CONSTRAINT ck_stockout_wave_state CHECK(state IN ('DRAFT','ALLOCATED','PICKED','CANCELLED'))
);

CREATE TABLE stockout_wave_order (
    wave_id BIGINT NOT NULL REFERENCES stockout_wave(id) ON DELETE CASCADE,
    order_id BIGINT NOT NULL REFERENCES stockout_order(id),
    PRIMARY KEY(wave_id,order_id),
    CONSTRAINT uk_stockout_wave_order UNIQUE(order_id)
);

CREATE TABLE stockout_request (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    order_id BIGINT REFERENCES stockout_order(id),
    action_type VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stockout_request UNIQUE(company_id,idempotency_key)
);

CREATE INDEX idx_stockout_order_tenant_state ON stockout_order(company_id,warehouse_id,owner_id,state,created_at);
CREATE INDEX idx_stockout_line_order ON stockout_order_line(order_id);
CREATE INDEX idx_stockout_allocation_order ON stockout_allocation(order_id,state);
CREATE INDEX idx_stockout_event_order ON stockout_event(order_id,created_at);
CREATE INDEX idx_stockout_package_order ON stockout_package(order_id,created_at);
CREATE INDEX idx_stockout_wave_tenant ON stockout_wave(company_id,warehouse_id,owner_id,created_at);

INSERT INTO auth_permission(code,name) SELECT 'stockout:read','查看出库单'
WHERE NOT EXISTS (SELECT 1 FROM auth_permission WHERE code='stockout:read');
INSERT INTO auth_permission(code,name) SELECT 'stockout:write','执行出库作业'
WHERE NOT EXISTS (SELECT 1 FROM auth_permission WHERE code='stockout:write');
INSERT INTO auth_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM auth_role r CROSS JOIN auth_permission p
WHERE p.code IN ('stockout:read','stockout:write')
AND NOT EXISTS (SELECT 1 FROM auth_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
INSERT INTO auth_menu(code,name,path,icon,required_permission,sort_order)
SELECT 'stockout','出库管理','/stockout','Upload','stockout:read',50
WHERE NOT EXISTS (SELECT 1 FROM auth_menu WHERE code='stockout');

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO wms_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO wms_app;
