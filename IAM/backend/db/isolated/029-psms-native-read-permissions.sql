-- Read grants for the published PSMS plan and overview adapters only.
-- Does not authorize exports, other resource reads, local roles or user mappings.
BEGIN;
INSERT INTO iam.iam_permission(id,node_type,permission_code,permission_name,system_code,status)
SELECT code,'BUTTON',code,name,'pps','active'
FROM (VALUES
 ('pps:api:plans:read','生产调度计划-读取'),
 ('pps:api:overview:read','生产调度总览-读取')
) AS v(code,name)
WHERE NOT EXISTS(SELECT 1 FROM iam.iam_permission p WHERE p.permission_code=v.code);

INSERT INTO iam.iam_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM iam.iam_role r CROSS JOIN iam.iam_permission p
WHERE lower(r.role_code)='platform_auditor_readonly' AND r.deleted=0 AND r.status='active'
 AND p.permission_code IN('pps:api:plans:read','pps:api:overview:read') AND p.status='active'
 AND NOT EXISTS(SELECT 1 FROM iam.iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
COMMIT;
