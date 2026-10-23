-- Native WMS read grants must participate in the current central/local intersection.
-- Does not grant export, tenant expansion, local roles, user assignments or mappings.
BEGIN;
INSERT INTO iam.iam_permission(id,node_type,permission_code,permission_name,system_code,status)
SELECT code,'BUTTON',code,name,'wms','active'
FROM (VALUES
 ('wms:dashboard:view','仓储工作台-查看'),('wms:inventory:read','库存-读取'),
 ('wms:system:user:read','仓储用户-读取'),('wms:master:read','基础资料-读取'),
 ('wms:stockin:read','入库-读取'),('wms:stockout:read','出库-读取'),
 ('wms:finance:read','财务-读取'),('wms:agent:read','仓储智能体-读取')
) AS v(code,name)
WHERE NOT EXISTS(SELECT 1 FROM iam.iam_permission p WHERE p.permission_code=v.code);

INSERT INTO iam.iam_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM iam.iam_role r CROSS JOIN iam.iam_permission p
WHERE lower(r.role_code)='platform_auditor_readonly' AND r.deleted=0 AND r.status='active'
 AND p.node_type='BUTTON' AND p.system_code='wms' AND p.status='active'
 AND (p.permission_code LIKE '%:view' OR p.permission_code LIKE '%:read')
 AND NOT EXISTS(SELECT 1 FROM iam.iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
COMMIT;
