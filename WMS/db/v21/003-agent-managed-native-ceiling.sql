-- Technical ceiling for the existing IAM-managed provisioning role.
-- Run after verifying every mapped IAM subject is V2 in the IAM database.
-- NativeOnboardingWorker already assigns this role to new managed subjects.
-- The live SSO guard still intersects every request with central menu/actions;
-- scheduled patrols additionally re-introspect the saved subject/session reference.
BEGIN;
DO $$ BEGIN
IF EXISTS (
  SELECT 1 FROM auth_user_role ur JOIN auth_role r ON r.id=ur.role_id
  JOIN auth_user u ON u.id=ur.user_id
  WHERE r.code='IAM_V2_MANAGED' AND
    (u.password_hash<>'!IAM_MANAGED_V2' OR NOT EXISTS (
      SELECT 1 FROM wms_sso_subject_map m WHERE m.local_user_id=u.id AND m.audience='wms'))
) THEN RAISE EXCEPTION 'IAM_MANAGED_ROLE_CONTAINS_LOCAL_OR_UNMAPPED_ACCOUNT'; END IF;
END $$;
INSERT INTO auth_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM auth_role r CROSS JOIN auth_permission p
WHERE r.code='IAM_V2_MANAGED' AND p.code IN ('agent:read','agent:manage','system:user:read')
AND NOT EXISTS (SELECT 1 FROM auth_role_permission x WHERE x.role_id=r.id AND x.permission_id=p.id);
COMMIT;

-- system:user:read exposes IAM navigation only for centrally approved L1/L2/L3;
-- WmsCentralScope keeps native user CRUD denied for every V2 subject.
