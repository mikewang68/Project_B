BEGIN;
CREATE TABLE wms_agent_task (
 id BIGSERIAL PRIMARY KEY, company_id BIGINT NOT NULL REFERENCES auth_company(id),
 warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id), owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
 user_id BIGINT NOT NULL REFERENCES auth_user(id), question VARCHAR(2000) NOT NULL,
 answer TEXT, tool_events TEXT NOT NULL DEFAULT '[]', state VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 finished_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_agent_task_user ON wms_agent_task(user_id,company_id,warehouse_id,owner_id,id);
CREATE TABLE wms_agent_schedule (
 id BIGSERIAL PRIMARY KEY, company_id BIGINT NOT NULL REFERENCES auth_company(id),
 warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id), owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
 user_id BIGINT NOT NULL REFERENCES auth_user(id), name VARCHAR(100) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, frequency VARCHAR(16) NOT NULL DEFAULT 'DAILY',
 interval_minutes INTEGER NOT NULL DEFAULT 60 CHECK(interval_minutes BETWEEN 5 AND 10080),
 daily_time VARCHAR(5) NOT NULL DEFAULT '08:00', emails VARCHAR(1000) NOT NULL DEFAULT '-',
 low_stock BOOLEAN NOT NULL DEFAULT TRUE, replenishment BOOLEAN NOT NULL DEFAULT TRUE,
 frozen_stock BOOLEAN NOT NULL DEFAULT TRUE, capacity BOOLEAN NOT NULL DEFAULT TRUE,
 capacity_percent NUMERIC(6,2) NOT NULL DEFAULT 85 CHECK(capacity_percent BETWEEN 1 AND 150),
 cooldown_hours INTEGER NOT NULL DEFAULT 24 CHECK(cooldown_hours BETWEEN 1 AND 168),
 next_run_at TIMESTAMP WITH TIME ZONE NOT NULL, last_run_at TIMESTAMP WITH TIME ZONE,
 lease_until TIMESTAMP WITH TIME ZONE,
 last_error VARCHAR(500), created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_agent_schedule_due ON wms_agent_schedule(enabled,next_run_at);
CREATE TABLE wms_agent_alert (
 id BIGSERIAL PRIMARY KEY, schedule_id BIGINT NOT NULL REFERENCES wms_agent_schedule(id),
 company_id BIGINT NOT NULL, warehouse_id BIGINT NOT NULL, owner_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL, object_key VARCHAR(200) NOT NULL, kind VARCHAR(32) NOT NULL,
 title VARCHAR(200) NOT NULL, body TEXT NOT NULL, level VARCHAR(16) NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'OPEN', read_at TIMESTAMP WITH TIME ZONE,
 last_notified_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(schedule_id,object_key)
);
CREATE INDEX idx_agent_alert_user ON wms_agent_alert(user_id,company_id,warehouse_id,owner_id,id);
CREATE TABLE wms_agent_mail (
 id BIGSERIAL PRIMARY KEY, schedule_id BIGINT NOT NULL REFERENCES wms_agent_schedule(id),
 user_id BIGINT NOT NULL, recipients VARCHAR(1000) NOT NULL, subject VARCHAR(200) NOT NULL,
 body TEXT NOT NULL, state VARCHAR(20) NOT NULL DEFAULT 'PENDING', attempts INTEGER NOT NULL DEFAULT 0,
 next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_error VARCHAR(500), sent_at TIMESTAMP WITH TIME ZONE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_agent_mail_due ON wms_agent_mail(state,next_attempt_at);
CREATE TABLE wms_agent_snapshot (
 id BIGSERIAL PRIMARY KEY, company_id BIGINT NOT NULL, warehouse_id BIGINT NOT NULL, owner_id BIGINT NOT NULL,
 occupied_locations INTEGER NOT NULL, total_locations INTEGER NOT NULL, known_weight_kg NUMERIC(24,3) NOT NULL,
 unknown_weight_rows INTEGER NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_agent_snapshot_scope ON wms_agent_snapshot(company_id,warehouse_id,owner_id,created_at);
INSERT INTO auth_permission(code,name) VALUES ('agent:read','使用仓储智能体'),('agent:manage','配置智能巡检');
INSERT INTO auth_role_permission(role_id,permission_id)
 SELECT r.id,p.id FROM auth_role r CROSS JOIN auth_permission p
 WHERE p.code='agent:read' AND EXISTS(SELECT 1 FROM auth_role_permission rp JOIN auth_permission ip ON ip.id=rp.permission_id WHERE rp.role_id=r.id AND ip.code='inventory:read');
INSERT INTO auth_role_permission(role_id,permission_id)
 SELECT r.id,p.id FROM auth_role r CROSS JOIN auth_permission p WHERE p.code='agent:manage' AND r.code IN ('ADMIN','MANAGER');
INSERT INTO auth_menu(code,name,path,icon,required_permission,sort_order)
 VALUES ('warehouse-agent','仓储智能体','/agent','ChatDotRound','agent:read',25);
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO wms_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO wms_app;
COMMIT;
