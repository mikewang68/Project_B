-- Register actual SCS routes. New pages stay unavailable until explicitly reviewed.
BEGIN;
SET LOCAL search_path TO iam,public;
INSERT INTO iam_navigation_menu(
 stable_menu_key,navigation_app_code,source_app_code,source_menu_id,source_parent_id,
 parent_stable_menu_key,hierarchy_code,original_level,normalized_level,sibling_sort_order,
 menu_name,projected_display_title,menu_kind,entry_mode,route_path,entry_path,page_key,
 visible,enabled,valid,implementation_status,source_reference,content_sha256,last_batch_id,
 feature_status,unavailable_reason,nav_eligible)
SELECT 'SCS:safety-screen','SCS','SCS','safety-screen','SCS:dir-display','SCS:dir-display',
 'SCS.DISPLAY.SCREEN',3,3,
 (SELECT COALESCE(MAX(sibling_sort_order),0)+10 FROM iam_navigation_menu
  WHERE navigation_app_code='SCS' AND parent_stable_menu_key='SCS:dir-display' AND normalized_level=3),
 '安全大屏','安全大屏','PAGE','SPA_ROUTE',
 '/safety-screen','/safety/safety-screen','safety-screen',1,1,1,'UNKNOWN',
 'SCS/frontend/src/router/index.ts',repeat('0',64),'SCS_FEATURE_ROUTES_V1',
 'PENDING_VERIFICATION','等待页面、接口及权限核验',0
WHERE NOT EXISTS(SELECT 1 FROM iam_navigation_menu WHERE stable_menu_key='SCS:safety-screen');

INSERT INTO iam_navigation_menu_permission(
 mapping_id,stable_menu_key,navigation_app_code,permission_scope,inter_group_operator,
 rule_group_no,rule_operator,condition_seq,source_permission_raw,candidate_permission_code,
 existing_iam_permission_id,existing_iam_system_code,match_kind,mapping_status,approval_status,
 requires_readonly_design,valid,source_evidence,content_sha256,last_batch_id)
SELECT 'SCS_FEATURE_'||v.page||'_VIEW','SCS:'||v.page,'SCS','VIEW','ALL_GROUPS',1,'SINGLE',1,
 v.permission,v.permission,p.id,'safety','EXACT','PROPOSED','PENDING_DECISION',0,1,
 'SCS/frontend/src/permissions.ts; SCS/backend/src/main/java',repeat('0',64),'SCS_FEATURE_ROUTES_V1'
FROM (VALUES
 ('people','safety:person:map:view'),('fences','safety:fence:list:view'),
 ('devices','safety:module:collision:view'),('ai','safety:module:ai:view'),
 ('ai-models','safety:module:ai:model:view'),('alarms','safety:module:alert:view'),
 ('analytics','safety:module:stat:view'),('rules','safety:module:rule:view'),
 ('operations','safety:module:ops:view'),('safety-screen','safety:screen:view'),
 ('mobile-home','safety:mobile:view'),('mobile-alerts','safety:mobile:view')
)v(page,permission)
JOIN iam_permission p ON p.permission_code=v.permission AND p.status='active'
WHERE EXISTS(SELECT 1 FROM iam_navigation_menu m WHERE m.stable_menu_key='SCS:'||v.page)
AND NOT EXISTS(SELECT 1 FROM iam_navigation_menu_permission mp
 WHERE mp.stable_menu_key='SCS:'||v.page AND mp.permission_scope='VIEW' AND mp.valid=1);
COMMIT;
