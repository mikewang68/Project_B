-- Explicit application grants and management ownership. No account/password seeds.
SET search_path TO iam,public;
BEGIN;
CREATE TABLE IF NOT EXISTS iam_role_app_scope (
 role_id VARCHAR(32) NOT NULL,app_code VARCHAR(16) NOT NULL,
 access_level VARCHAR(16) NOT NULL DEFAULT 'BUSINESS' CHECK(access_level IN ('BUSINESS','ADMIN')),
 PRIMARY KEY(role_id,app_code),
 CHECK(app_code IN ('WMS','PSMS','SCS','EHMS','EMCS','TRUST','IAM','SYS'))
);
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='iam' AND table_name='iam_role_app_scope' AND column_name='access_level') THEN
  ALTER TABLE iam_role_app_scope ADD COLUMN access_level VARCHAR(16) NOT NULL DEFAULT 'BUSINESS';
 END IF;
END $$;
CREATE TABLE IF NOT EXISTS iam_management_owner (
 object_kind VARCHAR(8) NOT NULL,object_id VARCHAR(128) NOT NULL,
 app_code VARCHAR(16) NOT NULL,
 PRIMARY KEY(object_kind,object_id),
 CHECK(object_kind IN ('USER','ROLE')),
 CHECK(app_code IN ('GLOBAL','WMS','PSMS','SCS','EHMS','EMCS','TRUST','IAM','SYS'))
);
-- Record the current explicit permission catalog once. Re-running cannot restore revoked grants.
CREATE TABLE IF NOT EXISTS iam_scope_migration (id VARCHAR(64) PRIMARY KEY);
INSERT INTO iam_role_app_scope(role_id,app_code)
SELECT DISTINCT r.id,a.app FROM iam_role r
JOIN iam_role_permission rp ON rp.role_id=r.id
JOIN iam_permission p ON p.id=rp.permission_id AND p.status='active'
JOIN (VALUES ('wms','WMS'),('pps','PSMS'),('safety','SCS'),('ehealth','EHMS'),
 ('ems','EMCS'),('trust','TRUST'),('iam','IAM'),('sys','SYS')) a(code,app) ON a.code=p.system_code
WHERE r.deleted=0 AND NOT EXISTS(SELECT 1 FROM iam_scope_migration WHERE id='033-initial-scopes')
AND NOT EXISTS(SELECT 1 FROM iam_role_app_scope s WHERE s.role_id=r.id AND s.app_code=a.app);
INSERT INTO iam_role_app_scope(role_id,app_code)
SELECT r.id,a.app FROM iam_role r CROSS JOIN
 (VALUES ('WMS'),('PSMS'),('SCS'),('EHMS'),('EMCS'),('TRUST'),('IAM'),('SYS')) a(app)
WHERE upper(r.role_code) IN ('SUPER_ADMIN','PLATFORM_ADMIN_FULL','PLATFORM_AUDITOR_READONLY') AND r.deleted=0
AND NOT EXISTS(SELECT 1 FROM iam_scope_migration WHERE id='033-initial-scopes')
AND NOT EXISTS(SELECT 1 FROM iam_role_app_scope s WHERE s.role_id=r.id AND s.app_code=a.app);
INSERT INTO iam_management_owner(object_kind,object_id,app_code)
SELECT 'USER',u.id,'GLOBAL' FROM iam_user u WHERE NOT EXISTS
 (SELECT 1 FROM iam_management_owner o WHERE o.object_kind='USER' AND o.object_id=u.id);
INSERT INTO iam_management_owner(object_kind,object_id,app_code)
SELECT 'ROLE',r.id,'GLOBAL' FROM iam_role r WHERE NOT EXISTS
 (SELECT 1 FROM iam_management_owner o WHERE o.object_kind='ROLE' AND o.object_id=r.id);
INSERT INTO iam_scope_migration(id) SELECT '033-initial-scopes'
WHERE NOT EXISTS(SELECT 1 FROM iam_scope_migration WHERE id='033-initial-scopes');
COMMIT;
