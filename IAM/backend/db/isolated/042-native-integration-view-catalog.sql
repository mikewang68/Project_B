-- Register native read entries only. Release review and role grants are separate.
BEGIN;
INSERT INTO iam.iam_navigation_menu
 (stable_menu_key,navigation_app_code,source_app_code,source_menu_id,
  source_parent_id,parent_stable_menu_key,hierarchy_code,original_level,
  normalized_level,sibling_sort_order,menu_name,projected_display_title,
  menu_kind,entry_mode,route_path,entry_path,page_key,implementation_status,
  source_reference,content_sha256,last_operation_source,updated_by,last_batch_id,
  feature_status,unavailable_reason)
SELECT 'TRUST:'||v.page,'TRUST','TRUST',v.page,'trust','APP:TRUST',
 'TRUST.NATIVE.'||v.page,2,2,v.ordering,v.title,v.title,'PAGE','PAGE_KEY','/',
 '/?navTab='||v.page,v.page,'IMPLEMENTED',
 'TRUST/frontend/src/app/pageLocation.ts; TRUST/frontend/src/app/navigation.ts',
 repeat('0',64),'SYSTEM_MIGRATION','CATALOG_MIGRATION','NATIVE_READ_CATALOG_V1',
 'READY','Native page requires release verification'
FROM (VALUES
 ('trace',20,'批次溯源'),('tasks',30,'补办任务'),('status',40,'运行状态'),
 ('wallets',50,'托管钱包'),('identities',60,'用户身份')
) v(page,ordering,title)
WHERE EXISTS(SELECT 1 FROM iam.iam_navigation_menu WHERE stable_menu_key='APP:TRUST')
AND NOT EXISTS(SELECT 1 FROM iam.iam_navigation_menu m WHERE m.stable_menu_key='TRUST:'||v.page);

INSERT INTO iam.iam_navigation_menu_permission
 (mapping_id,stable_menu_key,navigation_app_code,permission_scope,
  source_permission_raw,candidate_permission_code,existing_iam_permission_id,
  existing_iam_system_code,match_kind,mapping_status,approval_status,
  source_evidence,content_sha256,last_operation_source,updated_by,last_batch_id)
SELECT 'NATIVE_READ_'||replace(v.key,':','_'),v.key,m.navigation_app_code,'VIEW',
 v.code,p.permission_code,p.id,p.system_code,'CODE_ALIAS','PROPOSED','PENDING_DECISION',
 'Native application routes and backend permission catalog',repeat('0',64),
 'SYSTEM_MIGRATION','CATALOG_MIGRATION','NATIVE_READ_CATALOG_V1'
FROM (VALUES
 ('EMCS:2001','ems:energy:overview:view'),
 ('EMCS:2004','ems:energy:analysis:profile'),
 ('EMCS:2008','ems:energy:collect:raw:view'),
 ('EMCS:2011','ems:energy:alert:suggestion'),
 ('EMCS:2013','ems:energy:cost:record'),
 ('EMCS:2014','ems:energy:cost:report'),
 ('EMCS:2015','ems:energy:agent:inspection'),
 ('TRUST:trace','trust:event:read'),('TRUST:tasks','trust:task:read'),
 ('TRUST:status','trust:operations:read'),('TRUST:wallets','trust:wallet:read'),
 ('TRUST:identities','trust:identity:read')
) v(key,code)
JOIN iam.iam_navigation_menu m ON m.stable_menu_key=v.key
JOIN iam.iam_permission p ON p.permission_code=v.code AND p.status='active'
WHERE NOT EXISTS(SELECT 1 FROM iam.iam_navigation_menu_permission r
 WHERE r.stable_menu_key=v.key AND r.permission_scope='VIEW' AND r.valid=1);
COMMIT;
