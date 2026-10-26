-- Catalog only. Assign roles and approve navigation entries after their scoped adapters are verified.
-- Does not grant exports, execution, approval, business writes or wider application scope.
BEGIN;
INSERT INTO iam.iam_permission(id,node_type,permission_code,permission_name,system_code,status)
SELECT code,'BUTTON',code,name,'pps','active'
FROM (VALUES
 ('pps:api:work-orders:read','生产调度工单-读取'),
 ('pps:api:monitor:read','生产调度任务与设备-读取'),
 ('pps:api:appointments:read','生产调度车辆预约-读取'),
 ('pps:api:exceptions:read','生产调度异常-读取')
) AS v(code,name)
WHERE NOT EXISTS(SELECT 1 FROM iam.iam_permission p WHERE p.permission_code=v.code);
COMMIT;
