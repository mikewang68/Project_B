ALTER TABLE wms_warehouse ADD COLUMN contact VARCHAR(64);
ALTER TABLE wms_warehouse ADD COLUMN telephone VARCHAR(32);
ALTER TABLE wms_warehouse ADD COLUMN email VARCHAR(128);
ALTER TABLE wms_warehouse ADD COLUMN address VARCHAR(256);
ALTER TABLE wms_warehouse ADD COLUMN express_code VARCHAR(64);
ALTER TABLE wms_warehouse ADD COLUMN remark VARCHAR(256);
ALTER TABLE wms_warehouse ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE wms_owner ADD COLUMN contact VARCHAR(64);
ALTER TABLE wms_owner ADD COLUMN telephone VARCHAR(32);
ALTER TABLE wms_owner ADD COLUMN email VARCHAR(128);
ALTER TABLE wms_owner ADD COLUMN address VARCHAR(256);
ALTER TABLE wms_owner ADD COLUMN remark VARCHAR(256);
ALTER TABLE wms_owner ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE IF NOT EXISTS wms_area (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    area_type VARCHAR(16) NOT NULL DEFAULT 'STORAGE',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    remark VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wms_area_warehouse_code UNIQUE (warehouse_id, code),
    CONSTRAINT ck_wms_area_type CHECK (area_type IN ('STORAGE','TEMP','PIECE','BAD','RETURN','REPAIR')),
    CONSTRAINT ck_wms_area_status CHECK (status IN ('ENABLED','DISABLED','DELETED'))
);

CREATE TABLE IF NOT EXISTS wms_work_area (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    system_defined BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    remark VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wms_work_area_warehouse_code UNIQUE (warehouse_id, code),
    CONSTRAINT ck_wms_work_area_status CHECK (status IN ('ENABLED','DISABLED','DELETED'))
);

CREATE TABLE IF NOT EXISTS wms_location (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    area_id BIGINT NOT NULL REFERENCES wms_area(id),
    work_area_id BIGINT NOT NULL REFERENCES wms_work_area(id),
    code VARCHAR(64) NOT NULL,
    operation_order INTEGER NOT NULL DEFAULT 0,
    priority VARCHAR(2) NOT NULL DEFAULT 'L3',
    system_defined BOOLEAN NOT NULL DEFAULT FALSE,
    length_cm NUMERIC(12,3) NOT NULL DEFAULT 0,
    width_cm NUMERIC(12,3) NOT NULL DEFAULT 0,
    height_cm NUMERIC(12,3) NOT NULL DEFAULT 0,
    max_weight_kg NUMERIC(14,3) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    remark VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wms_location_warehouse_code UNIQUE (warehouse_id, code),
    CONSTRAINT ck_wms_location_priority CHECK (priority IN ('L1','L2','L3','L4','L5')),
    CONSTRAINT ck_wms_location_status CHECK (status IN ('ENABLED','DISABLED','DELETED'))
);

CREATE TABLE IF NOT EXISTS wms_partner (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(256) NOT NULL,
    partner_type VARCHAR(32) NOT NULL DEFAULT 'CLIENT',
    contact VARCHAR(64),
    telephone VARCHAR(32),
    email VARCHAR(128),
    address VARCHAR(256),
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    remark VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wms_partner_company_code UNIQUE (company_id, code),
    CONSTRAINT ck_wms_partner_type CHECK (partner_type IN ('CLIENT','SUPPLIER','CLIENT_SUPPLIER','EXPRESS')),
    CONSTRAINT ck_wms_partner_status CHECK (status IN ('ENABLED','DISABLED','DELETED'))
);

CREATE TABLE IF NOT EXISTS wms_category (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    remark VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wms_category_owner_code UNIQUE (owner_id, code),
    CONSTRAINT ck_wms_category_status CHECK (status IN ('ENABLED','DISABLED','DELETED'))
);

CREATE TABLE IF NOT EXISTS wms_good (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    category_id BIGINT NOT NULL REFERENCES wms_category(id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(256) NOT NULL,
    barcode VARCHAR(128) NOT NULL,
    specification VARCHAR(128),
    unit VARCHAR(32),
    brand VARCHAR(64),
    item_type VARCHAR(16) NOT NULL DEFAULT 'ZC',
    shelf_life_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    quality_months INTEGER NOT NULL DEFAULT 0,
    min_quantity NUMERIC(18,3) NOT NULL DEFAULT 0,
    max_quantity NUMERIC(18,3) NOT NULL DEFAULT 0,
    weight_kg NUMERIC(14,3) NOT NULL DEFAULT 0,
    price NUMERIC(18,4) NOT NULL DEFAULT 0,
    cost_price NUMERIC(18,4) NOT NULL DEFAULT 0,
    default_area_id BIGINT REFERENCES wms_area(id),
    default_location_id BIGINT REFERENCES wms_location(id),
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    remark VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wms_good_owner_code UNIQUE (owner_id, code),
    CONSTRAINT uk_wms_good_owner_barcode UNIQUE (owner_id, barcode),
    CONSTRAINT ck_wms_good_status CHECK (status IN ('ENABLED','DISABLED','DELETED'))
);

CREATE TABLE IF NOT EXISTS wms_good_component (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    parent_good_id BIGINT NOT NULL REFERENCES wms_good(id) ON DELETE CASCADE,
    component_good_id BIGINT NOT NULL REFERENCES wms_good(id),
    quantity NUMERIC(18,3) NOT NULL DEFAULT 1,
    remark VARCHAR(256),
    CONSTRAINT uk_wms_good_component UNIQUE (parent_good_id, component_good_id),
    CONSTRAINT ck_wms_good_component_not_self CHECK (parent_good_id <> component_good_id),
    CONSTRAINT ck_wms_good_component_qty CHECK (quantity > 0)
);

INSERT INTO wms_area(company_id, warehouse_id, code, name, area_type)
SELECT w.company_id, w.id, 'default', '默认库区', 'STORAGE' FROM wms_warehouse w
WHERE NOT EXISTS (SELECT 1 FROM wms_area a WHERE a.warehouse_id=w.id AND a.code='default');
INSERT INTO wms_work_area(company_id, warehouse_id, code, name, system_defined)
SELECT w.company_id, w.id, 'default', '默认工作区', TRUE FROM wms_warehouse w
WHERE NOT EXISTS (SELECT 1 FROM wms_work_area a WHERE a.warehouse_id=w.id AND a.code='default');
INSERT INTO wms_location(company_id, warehouse_id, area_id, work_area_id, code, system_defined, remark)
SELECT w.company_id, w.id, a.id, wa.id, 'STAGE', TRUE, '暂存库位'
FROM wms_warehouse w JOIN wms_area a ON a.warehouse_id=w.id AND a.code='default'
JOIN wms_work_area wa ON wa.warehouse_id=w.id AND wa.code='default'
WHERE NOT EXISTS (SELECT 1 FROM wms_location l WHERE l.warehouse_id=w.id AND l.code='STAGE');
INSERT INTO wms_category(company_id, owner_id, code, name)
SELECT o.company_id, o.id, 'default', '默认货类' FROM wms_owner o
WHERE NOT EXISTS (SELECT 1 FROM wms_category c WHERE c.owner_id=o.id AND c.code='default');

INSERT INTO auth_permission(code,name) SELECT 'master:read','查看仓库基础资料'
WHERE NOT EXISTS (SELECT 1 FROM auth_permission WHERE code='master:read');
INSERT INTO auth_permission(code,name) SELECT 'master:write','维护仓库基础资料'
WHERE NOT EXISTS (SELECT 1 FROM auth_permission WHERE code='master:write');
INSERT INTO auth_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM auth_role r CROSS JOIN auth_permission p
WHERE p.code IN ('master:read','master:write')
AND NOT EXISTS (SELECT 1 FROM auth_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
INSERT INTO auth_menu(code,name,path,icon,required_permission,sort_order)
SELECT 'master-data','基础资料','/master-data','OfficeBuilding','master:read',30
WHERE NOT EXISTS (SELECT 1 FROM auth_menu WHERE code='master-data');

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO wms_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO wms_app;
