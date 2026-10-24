-- =============================================================================
-- 011-sso-subject-binding-seed.sql — V1.1 显式主体映射种子（隔离演示用）
--
-- 背景
--   V1.1 删除了「导航树可见即视为有映射」的回退。因此必须为演示账号显式登记
--   到目标系统的本地受限主体，否则换票一律 403。
--
-- 重要：subject 是 iam_user.id（UUID），不是 username。
--   本脚本按 username 查 id 再写入，避免把用户名误当主键。
--
-- 内容（wms 受众）
--     admin       -> WMS 本地 admin
--     auditor     -> WMS 本地 auditor（必须有独立的只读业务账号，不继承 admin）
--     scoped_user -> 刻意不建映射，用于验证「无映射即 403」负例
--
-- 安全
--   - 只登记映射关系，不授予任何权限。
--   - 幂等（openGauss 无 ON CONFLICT，改用 UPDATE + INSERT ... WHERE NOT EXISTS）。
--   - 不含凭据、密码或哈希。
-- =============================================================================

SET search_path TO iam, public;
BEGIN;

-- admin -> wms/admin
UPDATE iam.sso_subject_binding b
   SET local_subject='admin', company_code='default', org_code='', domain_code='', zone_code='',
       enabled=1, updated_at=CURRENT_TIMESTAMP
  FROM iam.iam_user u
 WHERE u.username='admin' AND b.subject=u.id AND b.audience='wms';

INSERT INTO iam.sso_subject_binding
    (subject, audience, local_subject, company_code, org_code, domain_code, zone_code, enabled)
SELECT u.id,'wms','admin','default','','','',1
  FROM iam.iam_user u
 WHERE u.username='admin'
   AND NOT EXISTS (SELECT 1 FROM iam.sso_subject_binding b
                    WHERE b.subject=u.id AND b.audience='wms');

-- auditor -> wms/auditor
UPDATE iam.sso_subject_binding b
   SET local_subject='auditor', company_code='default', org_code='', domain_code='', zone_code='',
       enabled=1, updated_at=CURRENT_TIMESTAMP
  FROM iam.iam_user u
 WHERE u.username='auditor' AND b.subject=u.id AND b.audience='wms';

INSERT INTO iam.sso_subject_binding
    (subject, audience, local_subject, company_code, org_code, domain_code, zone_code, enabled)
SELECT u.id,'wms','auditor','default','','','',1
  FROM iam.iam_user u
 WHERE u.username='auditor'
   AND NOT EXISTS (SELECT 1 FROM iam.sso_subject_binding b
                    WHERE b.subject=u.id AND b.audience='wms');

-- 清理历史误写（先前把 username 当 subject 的行）
DELETE FROM iam.sso_subject_binding b
 WHERE b.audience='wms'
   AND b.subject IN ('admin','auditor','scoped_user')
   AND NOT EXISTS (SELECT 1 FROM iam.iam_user u WHERE u.id=b.subject);

-- 断言
DO $$
DECLARE n int; bad int;
BEGIN
  SELECT count(*) INTO n
    FROM iam.sso_subject_binding b JOIN iam.iam_user u ON u.id=b.subject
   WHERE b.audience='wms' AND b.enabled=1 AND u.username IN ('admin','auditor');
  IF n <> 2 THEN RAISE EXCEPTION 'expected 2 enabled wms bindings keyed by user id, got %', n; END IF;

  SELECT count(*) INTO bad FROM iam.sso_subject_binding
   WHERE audience='wms' AND subject NOT IN (SELECT id FROM iam.iam_user);
  IF bad <> 0 THEN RAISE EXCEPTION 'found % bindings whose subject is not a user id', bad; END IF;

  IF EXISTS (SELECT 1 FROM iam.sso_subject_binding b JOIN iam.iam_user u ON u.id=b.subject
              WHERE u.username='scoped_user' AND b.audience='wms') THEN
    RAISE EXCEPTION 'scoped_user must NOT have a wms binding in this seed';
  END IF;
END $$;

COMMIT;
