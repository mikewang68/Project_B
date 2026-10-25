-- Explicit EMCS management reads supported by the native SSO route policy.
-- Namespaced grants do not authorize writes, exports, or bypass native data scope.
BEGIN;
-- Repair earlier derived grants whose names end in :view but represent export.
-- This canonical role promises no export; independently granted roles are untouched.
DELETE FROM iam.iam_role_permission rp
USING iam.iam_role r,iam.iam_permission p
WHERE rp.role_id=r.id AND rp.permission_id=p.id
 AND lower(r.role_code)='platform_auditor_readonly'
 AND lower(p.permission_code) LIKE '%export%';

INSERT INTO iam.iam_permission(id,node_type,permission_code,permission_name,system_code,status)
SELECT code,'BUTTON',code,name,'ems','active'
FROM (VALUES
 ('ems:system:user:list','EMCS users - list'),
 ('ems:system:user:query','EMCS users - details'),
 ('ems:system:role:list','EMCS roles - list'),
 ('ems:system:role:query','EMCS roles - details'),
 ('ems:system:menu:list','EMCS menus - list'),
 ('ems:system:menu:query','EMCS menus - details'),
 ('ems:system:dept:list','EMCS departments - list'),
 ('ems:system:dept:query','EMCS departments - details'),
 ('ems:system:post:list','EMCS posts - list'),
 ('ems:system:post:query','EMCS posts - details'),
 ('ems:system:config:list','EMCS configuration - list'),
 ('ems:system:config:query','EMCS configuration - details'),
 ('ems:system:notice:list','EMCS notices - list'),
 ('ems:system:notice:query','EMCS notices - details'),
 ('ems:system:dict:list','EMCS dictionaries - list'),
 ('ems:system:dict:query','EMCS dictionaries - details')
)AS v(code,name)
WHERE NOT EXISTS(SELECT 1 FROM iam.iam_permission p WHERE p.permission_code=v.code);

INSERT INTO iam.iam_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM iam.iam_role r CROSS JOIN iam.iam_permission p
WHERE lower(r.role_code)='platform_auditor_readonly' AND r.deleted=0 AND r.status='active'
 AND p.system_code='ems' AND p.status='active'
 AND p.permission_code IN (
  'ems:system:user:list','ems:system:user:query',
  'ems:system:role:list','ems:system:role:query',
  'ems:system:menu:list','ems:system:menu:query',
  'ems:system:dept:list','ems:system:dept:query',
  'ems:system:post:list','ems:system:post:query',
  'ems:system:config:list','ems:system:config:query',
  'ems:system:notice:list','ems:system:notice:query',
  'ems:system:dict:list','ems:system:dict:query')
 AND NOT EXISTS(SELECT 1 FROM iam.iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
COMMIT;
