-- 029: AI 服务配置（URL / 模型 / 密钥密文）
-- 幂等；密钥只存 AES-256-GCM 密文，接口只返回 last4。
-- 当前只写 PSMS；其他系统复制实现时使用各自数据库与 system_code。

CREATE TABLE IF NOT EXISTS public.ai_provider_config (
  id                 TEXT PRIMARY KEY,
  system_code        TEXT NOT NULL UNIQUE,
  base_url           TEXT NOT NULL DEFAULT '',
  model              TEXT NOT NULL DEFAULT '',
  api_key_ciphertext TEXT,
  api_key_last4      TEXT,
  enabled            BOOLEAN NOT NULL DEFAULT TRUE,
  timeout_ms         INTEGER NOT NULL DEFAULT 30000
                     CHECK (timeout_ms >= 1000 AND timeout_ms <= 120000),
  max_tokens         INTEGER NOT NULL DEFAULT 1500
                     CHECK (max_tokens >= 1 AND max_tokens <= 32000),
  version            INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  updated_by         TEXT,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  valid              SMALLINT NOT NULL DEFAULT 1 CHECK (valid IN (0, 1))
);

CREATE INDEX IF NOT EXISTS idx_ai_provider_config_system
  ON public.ai_provider_config (system_code);

COMMENT ON TABLE public.ai_provider_config IS
  '各系统 AI 服务配置；密钥只存 AES-256-GCM 密文，接口只返回 last4';
COMMENT ON COLUMN public.ai_provider_config.api_key_ciphertext IS
  'AES-256-GCM 密文（v1:iv:tag:data），禁止明文落库';
COMMENT ON COLUMN public.ai_provider_config.api_key_last4 IS
  '密钥后 4 位，仅用于页面识别，不参与调用';
