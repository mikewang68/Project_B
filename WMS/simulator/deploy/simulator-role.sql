-- Run only in the owner-approved WMS database after backup. No shared IAM changes.
BEGIN;
INSERT INTO auth_role(code,name,sort_order)
SELECT 'SIMULATOR_RECEIVE','模拟采集收货专用',900
WHERE NOT EXISTS(SELECT 1 FROM auth_role WHERE code='SIMULATOR_RECEIVE');
INSERT INTO auth_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM auth_role r CROSS JOIN auth_permission p
WHERE r.code='SIMULATOR_RECEIVE' AND p.code IN ('stockin:read','stockin:write','master:read')
AND NOT EXISTS(SELECT 1 FROM auth_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
COMMIT;
SELECT r.code,p.code FROM auth_role r JOIN auth_role_permission rp ON rp.role_id=r.id
JOIN auth_permission p ON p.id=rp.permission_id WHERE r.code='SIMULATOR_RECEIVE' ORDER BY p.code;
