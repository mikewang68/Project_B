-- =============================================================================
-- 010-sso-ticket.sql — SSO V1 票据与注销基础设施
--
-- 目的
--   为八系统可信 SSO 契约 V1 提供 IAM 侧的服务端表：
--     1. sso_ticket        一次性票据的签发记录（防重放 + 审计）
--     2. sso_ticket_consumed  目标系统可选的集中消费记录（V1 由目标系统自管）
--     3. sso_logout_notify 注销通知的投递记录（补偿重试用）
--
-- 契约
--   - 票据是 JWT（HS256），本表只记录 jti/sub/aud/exp，不存票据原文
--   - 消费是原子的：目标系统 INSERT 冲突即视为重放
--   - 本表不含任何密钥、密码或哈希
--
-- 适用
--   隔离 IAM 库。生产库执行前必须单独评审。
--
-- 幂等
--   全部 CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS
-- =============================================================================

SET search_path TO iam, public;
BEGIN;

-- ---------------------------------------------------------------------------
-- 1. 票据签发记录
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS iam.sso_ticket (
    jti             VARCHAR(64)  PRIMARY KEY,
    subject         VARCHAR(64)  NOT NULL,          -- IAM 用户主键
    username        VARCHAR(64)  NOT NULL DEFAULT '',
    audience        VARCHAR(32)  NOT NULL,          -- iam|sys|wms|psms|scs|ehms|emcs|trust
    session_id      VARCHAR(64)  NOT NULL DEFAULT '', -- iam_session.id，用于联动注销
    company_code    VARCHAR(64)  NOT NULL DEFAULT '',
    org_code        VARCHAR(64)  NOT NULL DEFAULT '',
    domain_code     VARCHAR(64)  NOT NULL DEFAULT '',
    zone_code       VARCHAR(64)  NOT NULL DEFAULT '',
    issued_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMPTZ  NOT NULL,
    -- 消费信息（由换取方在 exchange 成功后回写）
    consumed_at     TIMESTAMPTZ,
    consumed_by     VARCHAR(64)  NOT NULL DEFAULT '',
    revoked_at      TIMESTAMPTZ,
    issue_batch     VARCHAR(64)  NOT NULL DEFAULT 'SSO_V1'
);

CREATE INDEX IF NOT EXISTS idx_sso_ticket_subject  ON iam.sso_ticket (subject, issued_at DESC);
CREATE INDEX IF NOT EXISTS idx_sso_ticket_audience ON iam.sso_ticket (audience, issued_at DESC);
CREATE INDEX IF NOT EXISTS idx_sso_ticket_session  ON iam.sso_ticket (session_id);

-- ---------------------------------------------------------------------------
-- 2. 注销通知投递记录（补偿用）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS iam.sso_logout_notify (
    id              VARCHAR(64)  PRIMARY KEY,
    jti             VARCHAR(64)  NOT NULL,          -- 注销通知自身的 jti
    subject         VARCHAR(64)  NOT NULL,
    audience        VARCHAR(32)  NOT NULL,
    session_id      VARCHAR(64)  NOT NULL DEFAULT '',
    reason          VARCHAR(64)  NOT NULL DEFAULT 'PORTAL_LOGOUT',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempt_count   INTEGER      NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    last_error      VARCHAR(512) NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_sso_logout_pending
    ON iam.sso_logout_notify (delivered_at, created_at);

-- ---------------------------------------------------------------------------
-- 3. 受限主体映射（IAM 侧只做登记；每个业务系统各自持有自己的映射表）
--    这里登记“该 IAM 用户在目标系统是否有映射”，供 BFF 换票前预判。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS iam.sso_subject_binding (
    subject         VARCHAR(64)  NOT NULL,
    audience        VARCHAR(32)  NOT NULL,
    local_subject   VARCHAR(128) NOT NULL DEFAULT '',
    company_code    VARCHAR(64)  NOT NULL DEFAULT '',
    org_code        VARCHAR(64)  NOT NULL DEFAULT '',
    domain_code     VARCHAR(64)  NOT NULL DEFAULT '',
    zone_code       VARCHAR(64)  NOT NULL DEFAULT '',
    enabled         SMALLINT     NOT NULL DEFAULT 1,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (subject, audience)
);

-- ---------------------------------------------------------------------------
-- 4. Post-conditions
-- ---------------------------------------------------------------------------
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM information_schema.tables
   WHERE table_schema='iam'
     AND table_name IN ('sso_ticket','sso_logout_notify','sso_subject_binding');
  IF n <> 3 THEN
    RAISE EXCEPTION 'expected 3 sso tables, got %', n;
  END IF;
END $$;

COMMIT;
