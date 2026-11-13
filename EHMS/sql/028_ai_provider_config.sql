-- 028: EHMS AI 服务配置（URL / 模型 / 密钥密文）
-- 在 EHM_OPENGAUSS_SCHEMA（生产当前为 ehm_zxj_0916）下执行；
-- 密钥只存 AES-256-GCM 密文，接口只返回 last4。幂等。

CREATE TABLE IF NOT EXISTS ai_provider_config (
  id VARCHAR(64) PRIMARY KEY,
  system_code VARCHAR(32) NOT NULL UNIQUE,
  base_url VARCHAR(500) NOT NULL DEFAULT '',
  model VARCHAR(200) NOT NULL DEFAULT '',
  api_key_ciphertext TEXT,
  api_key_last4 VARCHAR(8),
  enabled SMALLINT NOT NULL DEFAULT 1,
  timeout_ms INTEGER NOT NULL DEFAULT 45000,
  max_tokens INTEGER NOT NULL DEFAULT 1800,
  provider VARCHAR(100),
  data_policy VARCHAR(32) NOT NULL DEFAULT 'anonymized',
  version INTEGER NOT NULL DEFAULT 1,
  updated_by VARCHAR(128),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  valid SMALLINT NOT NULL DEFAULT 1
);

COMMENT ON TABLE ai_provider_config IS
  'EHMS AI 服务配置；api_key_ciphertext 只存 AES-256-GCM 密文，页面只显示 last4';

-- 按部署账号授权；生产当前 App 账号为 ehm_app。
-- GRANT SELECT, INSERT, UPDATE, DELETE ON ai_provider_config TO ehm_app;
