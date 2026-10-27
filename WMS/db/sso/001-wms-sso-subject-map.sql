-- =============================================================================
-- 001-wms-sso-subject-map.sql — WMS 侧 SSO 主体映射与审计（契约 V1.1）
--
-- 作用
--   1. wms_sso_subject_map       IAM subject -> WMS 本地受限主体（显式、无回退）
--   2. wms_sso_exchange_audit    兑换审计（不含票据原文与任何凭据）
--
-- 设计要点
--   - 无映射 = 拒绝。绝不 fallback 到 admin。
--   - local_user_id 必须是 auth_user 中真实存在且启用的账号；兑换时二次校验。
--   - zone_code 用于租户/区域范围；为空表示"无区域范围"，不表示"全部区域"。
--   - 幂等：CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS。
--   - 不含密码、哈希或任何密钥。
--
-- 适用：隔离演示库。生产库执行前需评审。
-- =============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS wms_sso_subject_map (
    iam_subject     VARCHAR(64)  NOT NULL,
    audience        VARCHAR(16)  NOT NULL DEFAULT 'wms',
    local_user_id   BIGINT       NOT NULL,
    company_code    VARCHAR(64)  NOT NULL DEFAULT '',
    -- A-compatible Gauss treats empty strings as NULL. NULL denotes no zone claim;
    -- any nonblank zone remains unsupported and is rejected by the exchange service.
    zone_code       VARCHAR(64)  DEFAULT NULL,
    enabled         SMALLINT     NOT NULL DEFAULT 1,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (iam_subject, audience)
);

CREATE INDEX IF NOT EXISTS idx_wms_sso_map_local
    ON wms_sso_subject_map (local_user_id);

CREATE TABLE IF NOT EXISTS wms_sso_exchange_audit (
    id             BIGSERIAL    PRIMARY KEY,
    iam_subject    VARCHAR(64)  NOT NULL,
    local_user_id  BIGINT       NOT NULL DEFAULT 0,
    iam_session_id VARCHAR(64)  NOT NULL DEFAULT '',
    result         VARCHAR(32)  NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_wms_sso_audit_time
    ON wms_sso_exchange_audit (created_at DESC);

-- 映射必须由维护方依据 IAM 稳定主键与真实本地用户逐项登记。
-- 本迁移仅建表，不预置管理员映射，不将 auditor 映射为 admin。

COMMIT;

-- 验证
-- SELECT m.iam_subject, u.username, m.company_code, m.enabled
--   FROM wms_sso_subject_map m JOIN auth_user u ON u.id = m.local_user_id;
