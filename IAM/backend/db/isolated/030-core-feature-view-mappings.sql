-- Register explicit core feature visibility; never approve release or enable navigation.
-- Apply after importing the navigation catalog. Existing VIEW decisions are preserved.
BEGIN;
INSERT INTO iam.iam_permission(id,node_type,permission_code,permission_name,system_code,status)
SELECT code,'BUTTON',code,name,system_code,'active'
FROM (VALUES
 ('wms:dashboard:view','仓储工作台-查看','wms'),
 ('trust:event:read','存证事件-读取','trust'),
 ('safety:home:map:view','安全态势-查看','safety'),
 ('ehealth:cockpit:view','设备健康驾驶舱-查看','ehealth'),
 ('ems:energy:alert:list','能源异常告警-查看','ems')
) AS v(code,name,system_code)
WHERE NOT EXISTS(SELECT 1 FROM iam.iam_permission p WHERE p.permission_code=v.code);

INSERT INTO iam.iam_navigation_menu_permission
 (mapping_id,stable_menu_key,navigation_app_code,permission_scope,
  source_permission_raw,candidate_permission_code,existing_iam_permission_id,
  existing_iam_system_code,match_kind,mapping_status,approval_status,
  source_evidence,content_sha256,last_operation_source,updated_by,last_batch_id)
SELECT v.mapping_id,v.menu_key,m.navigation_app_code,'VIEW',v.source_code,v.code,p.id,
 p.system_code,CASE WHEN v.source_code='' THEN 'SEMANTIC_PROPOSAL'
                   WHEN v.source_code=v.code THEN 'EXACT' ELSE 'CODE_ALIAS' END,
 'PROPOSED','PENDING_DECISION',v.source_evidence,v.fingerprint,
 'SYSTEM_MIGRATION','CATALOG_MIGRATION','CORE_FEATURE_VIEW_V1'
FROM (VALUES
 ('CORE_WMS_DASHBOARD_VIEW','WMS:dashboard','dashboard:view','wms:dashboard:view',
  'WMS/WMS/frontend/src; WMS/WMS/src/main/java',
  '964a388bf4b56bc5c6204da4b6ccb8b641cba5a1673c7346f47e4351c67e1839'),
 ('CORE_TRUST_EVENTS_VIEW','TRUST:events','','trust:event:read',
  'TRUST/frontend/src; TRUST/backend/src/main/java',
  'c47ee10cecde1d1ae849b9a2a8de346e5e278bb5b3441eb3999f6e0722da3bc0'),
 ('CORE_SCS_OVERVIEW_VIEW','SCS:overview','safety:home:map:view','safety:home:map:view',
  'SCS/frontend/src; SCS/backend/src/main/java',
  '582a5b77bc25aea4e42c591f06694693d87a8cccdabcc16056c88cc788a75077'),
 ('CORE_EHMS_COCKPIT_VIEW','EHMS:cockpit','','ehealth:cockpit:view',
  'EHMS/web/app.js; EHMS/source/backend/src/main/java',
  '1cd8d3b478df2e5da7caeb3f190517cde194a878a6c6eee3b9dcc93420dd2613'),
 ('CORE_EMCS_ALERT_VIEW','EMCS:2010','energy:alert:list','ems:energy:alert:list',
  'EMCS/web/src; EMCS/backend-java/src/main/java',
  '1f0656afd7dabc19d5b18516a72e3823c70c54815050716e6b8214bd94ab6711')
) AS v(mapping_id,menu_key,source_code,code,source_evidence,fingerprint)
JOIN iam.iam_permission p ON p.permission_code=v.code
JOIN iam.iam_navigation_menu m ON m.stable_menu_key=v.menu_key
WHERE NOT EXISTS(SELECT 1 FROM iam.iam_navigation_menu_permission r
                 WHERE r.stable_menu_key=v.menu_key AND r.permission_scope='VIEW');

INSERT INTO iam.iam_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM iam.iam_role r CROSS JOIN iam.iam_permission p
WHERE lower(r.role_code)='platform_auditor_readonly' AND r.deleted=0 AND r.status='active'
 AND p.status='active'
 AND p.permission_code IN('wms:dashboard:view','trust:event:read','safety:home:map:view',
                         'ehealth:cockpit:view','ems:energy:alert:list')
 AND NOT EXISTS(SELECT 1 FROM iam.iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
COMMIT;
