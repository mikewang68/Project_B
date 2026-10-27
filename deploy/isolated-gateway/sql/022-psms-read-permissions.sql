-- Isolated integration only; requires 006 and the PSMS menus from 021.
-- Catalog and explicit VIEW rules only. No role grant, SSO binding, release
-- approval, existing mapping replacement, or change to historical DTS rows.
-- Fingerprint: SHA-256 of mapping_id|menu_key|source_code|code (UTF-8).
SET search_path TO iam, public;
BEGIN;

INSERT INTO iam_permission (id, node_type, permission_code, permission_name, system_code, status)
SELECT v.code, 'BUTTON', v.code, v.name, 'pps', 'active'
FROM (VALUES
  ('pps:overview:view', '生产调度总览-查看'),
  ('pps:plan:view', '生产调度到发台账-查看')
) AS v(code, name)
WHERE NOT EXISTS (SELECT 1 FROM iam_permission p WHERE p.permission_code = v.code);

INSERT INTO iam_navigation_menu_permission
  (mapping_id, stable_menu_key, navigation_app_code, permission_scope,
   source_permission_raw, candidate_permission_code, existing_iam_permission_id,
   existing_iam_system_code, match_kind, mapping_status, approval_status,
   source_evidence, content_sha256, last_operation_source, updated_by, last_batch_id)
SELECT v.mapping_id, v.menu_key, 'PSMS', 'VIEW', v.source_code, v.code, p.id,
       'pps', 'CODE_ALIAS', 'PROPOSED', 'PENDING_DECISION',
       'PSMS/src/real/LiveApp.tsx; PSMS/backend/src/routes/plans.routes.ts',
       v.fingerprint, 'SYSTEM_MIGRATION', 'ISOLATED_MIGRATION', 'PSMS_REAL_READ_V1'
FROM (VALUES
  ('PSMS_REAL_OVERVIEW_VIEW', 'PSMS:ui-001', 'overview:view', 'pps:overview:view', '8a02e4014d33fa3f9f05fa255787711b632e79b38591f76fbf9c436e6483f566'),
  ('PSMS_REAL_PLANS_VIEW', 'PSMS:ui-002', 'plan:view', 'pps:plan:view', '28421d2ed54aaa33acad750e5815294561078051b02145ad06d0b97ec6b43f64')
) AS v(mapping_id, menu_key, source_code, code, fingerprint)
JOIN iam_permission p ON p.permission_code = v.code
JOIN iam_navigation_menu m ON m.stable_menu_key = v.menu_key
WHERE NOT EXISTS (
  SELECT 1 FROM iam_navigation_menu_permission r
  WHERE r.stable_menu_key = v.menu_key AND r.permission_scope = 'VIEW'
);

COMMIT;
