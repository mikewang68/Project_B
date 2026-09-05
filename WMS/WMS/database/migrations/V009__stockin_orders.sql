ALTER TABLE inv_transaction DROP CONSTRAINT ck_inv_transaction_type;
ALTER TABLE inv_transaction ADD CONSTRAINT ck_inv_transaction_type CHECK (transaction_type IN ('ADJUST','MOVE_OUT','MOVE_IN','FREEZE','UNFREEZE','COUNT','STOCK_IN'));
ALTER TABLE inv_operation DROP CONSTRAINT ck_inv_operation_type;
ALTER TABLE inv_operation ADD CONSTRAINT ck_inv_operation_type CHECK (operation_type IN ('ADJUST','MOVE','FREEZE','UNFREEZE','COUNT','STOCK_IN'));

CREATE TABLE stockin_order (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    order_code VARCHAR(64) NOT NULL,
    external_order_code VARCHAR(64),
    related_order_code VARCHAR(64),
    inbound_type VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    source VARCHAR(16) NOT NULL DEFAULT 'CUSTOM',
    state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    partner_id BIGINT REFERENCES wms_partner(id),
    planned_date DATE,
    total_amount NUMERIC(18,4) NOT NULL DEFAULT 0,
    received_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    created_by BIGINT NOT NULL REFERENCES auth_user(id),
    remark VARCHAR(256),
    idempotency_key VARCHAR(80) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stockin_order_code UNIQUE (company_id,order_code),
    CONSTRAINT uk_stockin_order_idempotency UNIQUE (company_id,idempotency_key),
    CONSTRAINT ck_stockin_order_type CHECK (inbound_type IN ('PURCHASE','RETURN','TRANSFER','CONSIGN','CUSTOM','PRODUCE','MATERIAL_RETURN','FIX','BORROW_RETURN','NORMAL','COOP_RETURN','PRODUCE_RETURN')),
    CONSTRAINT ck_stockin_order_source CHECK (source IN ('ERP','CUSTOM','IMPORT','QUICK')),
    CONSTRAINT ck_stockin_order_state CHECK (state IN ('DRAFT','RECEIVING','COMPLETED','CANCELLED'))
);
CREATE UNIQUE INDEX uk_stockin_external_code ON stockin_order(company_id,warehouse_id,owner_id,external_order_code);

CREATE TABLE stockin_order_line (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES stockin_order(id) ON DELETE CASCADE,
    line_no INTEGER NOT NULL,
    good_id BIGINT NOT NULL REFERENCES wms_good(id),
    planned_qty NUMERIC(18,3) NOT NULL,
    received_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    preferred_location_id BIGINT REFERENCES wms_location(id),
    supplier_code VARCHAR(64) NOT NULL DEFAULT '-',
    quality_type VARCHAR(8) NOT NULL DEFAULT 'ZP',
    product_date DATE,
    expire_date DATE,
    batch_code VARCHAR(64) NOT NULL DEFAULT '-',
    unit_price NUMERIC(18,4) NOT NULL DEFAULT 0,
    remark VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stockin_order_line_no UNIQUE (order_id,line_no),
    CONSTRAINT ck_stockin_line_planned CHECK (planned_qty > 0),
    CONSTRAINT ck_stockin_line_received CHECK (received_qty >= 0),
    CONSTRAINT ck_stockin_line_quality CHECK (quality_type IN ('ZP','CC','DJ','ZT','JS','XS'))
);

CREATE TABLE stockin_receipt (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    order_id BIGINT NOT NULL REFERENCES stockin_order(id),
    order_line_id BIGINT NOT NULL REFERENCES stockin_order_line(id),
    operation_code VARCHAR(64) NOT NULL,
    balance_id BIGINT NOT NULL REFERENCES inv_balance(id),
    good_id BIGINT NOT NULL REFERENCES wms_good(id),
    location_id BIGINT NOT NULL REFERENCES wms_location(id),
    quantity NUMERIC(18,3) NOT NULL,
    batch_code VARCHAR(64) NOT NULL DEFAULT '-',
    quality_type VARCHAR(8) NOT NULL DEFAULT 'ZP',
    supplier_code VARCHAR(64) NOT NULL DEFAULT '-',
    lpn VARCHAR(64) NOT NULL DEFAULT '-',
    serial_count INTEGER NOT NULL DEFAULT 0,
    operator_user_id BIGINT NOT NULL REFERENCES auth_user(id),
    idempotency_key VARCHAR(128) NOT NULL,
    remark VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stockin_receipt_idempotency UNIQUE (company_id,idempotency_key),
    CONSTRAINT ck_stockin_receipt_qty CHECK (quantity > 0)
);

CREATE INDEX idx_stockin_order_tenant_state ON stockin_order(company_id,warehouse_id,owner_id,state,created_at);
CREATE INDEX idx_stockin_line_order ON stockin_order_line(order_id);
CREATE INDEX idx_stockin_receipt_order ON stockin_receipt(order_id,created_at);

INSERT INTO auth_permission(code,name) SELECT 'stockin:read','查看入库单'
WHERE NOT EXISTS (SELECT 1 FROM auth_permission WHERE code='stockin:read');
INSERT INTO auth_permission(code,name) SELECT 'stockin:write','执行入库作业'
WHERE NOT EXISTS (SELECT 1 FROM auth_permission WHERE code='stockin:write');
INSERT INTO auth_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM auth_role r CROSS JOIN auth_permission p
WHERE p.code IN ('stockin:read','stockin:write')
AND NOT EXISTS (SELECT 1 FROM auth_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
INSERT INTO auth_menu(code,name,path,icon,required_permission,sort_order)
SELECT 'stockin','入库管理','/stockin','Download','stockin:read',40
WHERE NOT EXISTS (SELECT 1 FROM auth_menu WHERE code='stockin');

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO wms_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO wms_app;
