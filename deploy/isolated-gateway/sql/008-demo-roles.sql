-- =============================================================================
-- 008-demo-roles.sql — isolated demo roles for acceptance testing
--
-- Why this file exists
--   005-permissions.sql seeds only `super_admin` (plus TRUST helper roles). The
--   isolated demo also needs `auditor` (read-only) and `scoped_user` (two pages),
--   which existed only in the running database. Without them a rebuilt
--   environment cannot reproduce the role-isolation acceptance criteria.
--
-- Permissions granted here mirror the running isolated database exactly:
--   auditor      7 read-only permissions  (view/list only, no add/edit/delete)
--   scoped_user  2 permissions            (user list + dict type view)
--
-- Safety
--   - Idempotent: guarded by WHERE NOT EXISTS on role_code / (role_id, permission_id).
--   - Adds no permission that is not already defined by 005.
--   - Does not touch super_admin.
--   - ISOLATED demo database only.
--   - No credentials, no password hashes.
-- =============================================================================

SET search_path TO iam, public;
BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Roles
-- ---------------------------------------------------------------------------
INSERT INTO iam_role (id, role_code, role_name, description, status, created_at, updated_at, deleted)
SELECT 'role-auditor', 'auditor', '只读审计员',
       '安全合规只读审计角色，全平台7页面只读拦截写操作',
       'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
 WHERE NOT EXISTS (SELECT 1 FROM iam_role WHERE role_code = 'auditor');

INSERT INTO iam_role (id, role_code, role_name, description, status, created_at, updated_at, deleted)
SELECT 'role-scoped-user', 'scoped_user', '受限业务员',
       '受限业务角色，仅允许访问用户列表和数据字典',
       'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
 WHERE NOT EXISTS (SELECT 1 FROM iam_role WHERE role_code = 'scoped_user');

-- ---------------------------------------------------------------------------
-- 2. Grant the exact read-only permission set
--    Only permissions that 005 already defines are referenced; a missing
--    permission code aborts the transaction instead of silently skipping.
-- ---------------------------------------------------------------------------
DO $$
DECLARE missing text;
BEGIN
  -- openGauss raises when SELECT ... INTO returns zero rows, so aggregate
  -- into a scalar and test it instead of relying on the NULL result.
  SELECT min(t.p) INTO missing FROM (
    VALUES ('iam:menu:tree:view'), ('iam:role:list:view'), ('iam:user:list:view'),
           ('sys:config:list:view'), ('sys:dict:item:view'), ('sys:dict:type:view'),
           ('sys:log:list:view')
  ) AS t(p)
  WHERE NOT EXISTS (SELECT 1 FROM iam_permission WHERE permission_code = t.p);
  IF missing IS NOT NULL THEN
    RAISE EXCEPTION 'auditor permission % is not defined (run 005 then 009)', missing;
  END IF;

  SELECT min(t.p) INTO missing FROM (
    VALUES ('iam:user:list:view'), ('sys:dict:type:view')
  ) AS t(p)
  WHERE NOT EXISTS (SELECT 1 FROM iam_permission WHERE permission_code = t.p);
  IF missing IS NOT NULL THEN
    RAISE EXCEPTION 'scoped_user permission % is not defined (run 005 then 009)', missing;
  END IF;
END $$;

INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM iam_role r, iam_permission p
 WHERE r.role_code = 'auditor'
   AND p.permission_code IN (
       'iam:menu:tree:view', 'iam:role:list:view', 'iam:user:list:view',
       'sys:config:list:view', 'sys:dict:item:view', 'sys:dict:type:view',
       'sys:log:list:view')
   AND NOT EXISTS (SELECT 1 FROM iam_role_permission x
                    WHERE x.role_id = r.id AND x.permission_id = p.id);

INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM iam_role r, iam_permission p
 WHERE r.role_code = 'scoped_user'
   AND p.permission_code IN ('iam:user:list:view', 'sys:dict:type:view')
   AND NOT EXISTS (SELECT 1 FROM iam_role_permission x
                    WHERE x.role_id = r.id AND x.permission_id = p.id);

-- ---------------------------------------------------------------------------
-- 3. Post-conditions
-- ---------------------------------------------------------------------------
DO $$
DECLARE n_aud int; n_scp int;
BEGIN
  SELECT count(*) INTO n_aud FROM iam_role_permission rp
   JOIN iam_role r ON r.id = rp.role_id WHERE r.role_code = 'auditor';
  SELECT count(*) INTO n_scp FROM iam_role_permission rp
   JOIN iam_role r ON r.id = rp.role_id WHERE r.role_code = 'scoped_user';

  IF n_aud <> 7 THEN RAISE EXCEPTION 'auditor expected 7 permissions, got %', n_aud; END IF;
  IF n_scp <> 2 THEN RAISE EXCEPTION 'scoped_user expected 2 permissions, got %', n_scp; END IF;
END $$;

COMMIT;
