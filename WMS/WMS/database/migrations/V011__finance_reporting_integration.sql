CREATE TABLE finance_account (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    organ VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    account_no VARCHAR(100) NOT NULL,
    long_name VARCHAR(320) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    remark VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_finance_account UNIQUE(company_id,warehouse_id,owner_id,long_name),
    CONSTRAINT ck_finance_account_status CHECK(status IN ('ENABLED','DISABLED'))
);

CREATE TABLE finance_money (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    money_code VARCHAR(64) NOT NULL,
    related_order_code VARCHAR(64),
    direction VARCHAR(16) NOT NULL,
    fee_type VARCHAR(32) NOT NULL,
    partner_id BIGINT REFERENCES wms_partner(id),
    partner_code VARCHAR(64),
    partner_name VARCHAR(120),
    pay_type VARCHAR(32),
    amount NUMERIC(18,4) NOT NULL,
    paid_amount NUMERIC(18,4) NOT NULL DEFAULT 0,
    bad_debt_amount NUMERIC(18,4) NOT NULL DEFAULT 0,
    state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    accounting_date DATE NOT NULL DEFAULT CURRENT_DATE,
    finished_at TIMESTAMP WITH TIME ZONE,
    created_by BIGINT NOT NULL REFERENCES auth_user(id),
    remark VARCHAR(256),
    idempotency_key VARCHAR(80) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_finance_money_code UNIQUE(company_id,money_code),
    CONSTRAINT uk_finance_money_idempotency UNIQUE(company_id,idempotency_key),
    CONSTRAINT ck_finance_money_direction CHECK(direction IN ('INCOME','OUTCOME')),
    CONSTRAINT ck_finance_money_amount CHECK(amount>=0 AND paid_amount>=0 AND bad_debt_amount>=0),
    CONSTRAINT ck_finance_money_state CHECK(state IN ('DRAFT','PROCESSING','COMPLETED','PART_COMPLETED','CANCELLED'))
);

CREATE TABLE finance_money_transaction (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    money_id BIGINT NOT NULL REFERENCES finance_money(id),
    transaction_code VARCHAR(64) NOT NULL,
    amount NUMERIC(18,4) NOT NULL,
    account_id BIGINT REFERENCES finance_account(id),
    account_long_name VARCHAR(320),
    reference_user VARCHAR(64),
    operator_user_id BIGINT NOT NULL REFERENCES auth_user(id),
    idempotency_key VARCHAR(80) NOT NULL,
    remark VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_finance_transaction_code UNIQUE(company_id,transaction_code),
    CONSTRAINT uk_finance_transaction_idempotency UNIQUE(company_id,idempotency_key),
    CONSTRAINT ck_finance_transaction_amount CHECK(amount>0)
);

CREATE TABLE async_task (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    task_code VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    task_type VARCHAR(16) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
    original_file_name VARCHAR(255),
    stored_file_name VARCHAR(255),
    processed_rows INTEGER NOT NULL DEFAULT 0,
    success_rows INTEGER NOT NULL DEFAULT 0,
    failure_rows INTEGER NOT NULL DEFAULT 0,
    error_message VARCHAR(2000),
    created_by BIGINT NOT NULL REFERENCES auth_user(id),
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_async_task_code UNIQUE(company_id,task_code),
    CONSTRAINT ck_async_task_type CHECK(task_type IN ('IMPORT','EXPORT')),
    CONSTRAINT ck_async_task_state CHECK(state IN ('PROCESSING','COMPLETED','FAILED'))
);

CREATE TABLE integration_qimen_config (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    customer_id VARCHAR(100) NOT NULL,
    app_key VARCHAR(100) NOT NULL DEFAULT 'MT-WMS',
    app_secret VARCHAR(255) NOT NULL,
    callback_url VARCHAR(500),
    payload_format VARCHAR(8) NOT NULL DEFAULT 'JSON',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL REFERENCES auth_user(id),
    updated_by BIGINT NOT NULL REFERENCES auth_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_qimen_config_owner UNIQUE(company_id,owner_id),
    CONSTRAINT uk_qimen_config_customer UNIQUE(customer_id),
    CONSTRAINT ck_qimen_config_format CHECK(payload_format IN ('JSON','XML'))
);

CREATE TABLE integration_qimen_log (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT REFERENCES auth_company(id),
    warehouse_id BIGINT REFERENCES wms_warehouse(id),
    owner_id BIGINT REFERENCES wms_owner(id),
    direction VARCHAR(8) NOT NULL,
    method_name VARCHAR(80) NOT NULL,
    request_id VARCHAR(80),
    related_order_code VARCHAR(64),
    request_body TEXT,
    response_body TEXT,
    success BOOLEAN NOT NULL,
    error_message VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_qimen_log_direction CHECK(direction IN ('IN','OUT'))
);

CREATE INDEX idx_finance_money_tenant ON finance_money(company_id,warehouse_id,owner_id,accounting_date,state);
CREATE INDEX idx_finance_transaction_money ON finance_money_transaction(money_id,created_at);
CREATE INDEX idx_async_task_tenant ON async_task(company_id,warehouse_id,owner_id,created_at);
CREATE INDEX idx_qimen_log_tenant ON integration_qimen_log(company_id,warehouse_id,owner_id,created_at);

INSERT INTO auth_permission(code,name) SELECT 'finance:read','查看财务与统计'
WHERE NOT EXISTS (SELECT 1 FROM auth_permission WHERE code='finance:read');
INSERT INTO auth_permission(code,name) SELECT 'finance:write','维护费用及收付款'
WHERE NOT EXISTS (SELECT 1 FROM auth_permission WHERE code='finance:write');
INSERT INTO auth_permission(code,name) SELECT 'integration:manage','管理导入导出与外部集成'
WHERE NOT EXISTS (SELECT 1 FROM auth_permission WHERE code='integration:manage');
INSERT INTO auth_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM auth_role r CROSS JOIN auth_permission p
WHERE p.code IN ('finance:read','finance:write','integration:manage')
AND NOT EXISTS (SELECT 1 FROM auth_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
INSERT INTO auth_menu(code,name,path,icon,required_permission,sort_order)
SELECT 'finance','财务统计','/finance','TrendCharts','finance:read',60
WHERE NOT EXISTS (SELECT 1 FROM auth_menu WHERE code='finance');
INSERT INTO auth_menu(code,name,path,icon,required_permission,sort_order)
SELECT 'integration','导入导出与集成','/integration','Connection','integration:manage',70
WHERE NOT EXISTS (SELECT 1 FROM auth_menu WHERE code='integration');

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO wms_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO wms_app;
