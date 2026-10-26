-- Register explicit native WMS page visibility. No role grants or feature approval.
BEGIN;
INSERT INTO iam.iam_navigation_menu_permission
 (mapping_id,stable_menu_key,navigation_app_code,permission_scope,
  source_permission_raw,candidate_permission_code,existing_iam_permission_id,
  existing_iam_system_code,match_kind,mapping_status,approval_status,
  source_evidence,content_sha256,last_operation_source,updated_by,last_batch_id)
SELECT 'WMS_NATIVE_'||v.page||'_VIEW','WMS:'||v.page,'WMS','VIEW',v.local_code,
 p.permission_code,p.id,p.system_code,'CODE_ALIAS','PROPOSED','PENDING_DECISION',
 'WMS/WMS/frontend/src/router/index.ts; WMS/WMS/src/main/java/com/mt/wms',
 repeat('0',64),'SYSTEM_MIGRATION','CATALOG_MIGRATION','WMS_NATIVE_VIEWS_V1'
FROM (VALUES
 ('inventory','inventory:read'),('master-data','master:read'),
 ('stockin','stockin:read'),('stockout','stockout:read'),
 ('finance','finance:read'),('integration','integration:manage'),
 ('users','system:user:read'),('warehouse-agent','agent:read')
) v(page,local_code)
JOIN iam.iam_permission p ON p.permission_code='wms:'||v.local_code AND p.system_code='wms' AND p.status='active'
WHERE EXISTS(SELECT 1 FROM iam.iam_navigation_menu m WHERE m.stable_menu_key='WMS:'||v.page)
AND NOT EXISTS(SELECT 1 FROM iam.iam_navigation_menu_permission r
 WHERE r.stable_menu_key='WMS:'||v.page AND r.permission_scope='VIEW' AND r.valid=1);
COMMIT;
