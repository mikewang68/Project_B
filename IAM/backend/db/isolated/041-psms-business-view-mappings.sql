-- Explicit page visibility for native PSMS business data. No approval or role grants.
BEGIN;
INSERT INTO iam.iam_navigation_menu_permission
 (mapping_id,stable_menu_key,navigation_app_code,permission_scope,
  source_permission_raw,candidate_permission_code,existing_iam_permission_id,
  existing_iam_system_code,match_kind,mapping_status,approval_status,
  source_evidence,content_sha256,last_operation_source,updated_by,last_batch_id)
SELECT 'PSMS_BUSINESS_'||v.page||'_VIEW','PSMS:'||v.page,'PSMS','VIEW',v.code,
 p.permission_code,p.id,p.system_code,'CODE_ALIAS','PROPOSED','PENDING_DECISION',
 'PSMS/src/real/BusinessDataPage.tsx; PSMS/backend/src/services/business-read.service.ts',
 repeat('0',64),'SYSTEM_MIGRATION','CATALOG_MIGRATION','PSMS_BUSINESS_VIEWS_V1'
FROM (VALUES
 ('ui-005','pps:api:work-orders:read'),('ui-006','pps:api:appointments:read'),
 ('ui-007','pps:api:monitor:read'),('ui-008','pps:api:exceptions:read')
) v(page,code)
JOIN iam.iam_permission p ON p.permission_code=v.code AND p.system_code='pps' AND p.status='active'
WHERE EXISTS(SELECT 1 FROM iam.iam_navigation_menu m WHERE m.stable_menu_key='PSMS:'||v.page)
AND NOT EXISTS(SELECT 1 FROM iam.iam_navigation_menu_permission r
 WHERE r.stable_menu_key='PSMS:'||v.page AND r.permission_scope='VIEW' AND r.valid=1);
COMMIT;
