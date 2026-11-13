-- V016: 仓储智能体模型服务配置（URL / 模型 / 可选密钥密文）
-- 密钥只存 AES-256-GCM 密文，接口只返回 last4；无配置行时回退 wms.agent.* 环境变量。
-- 生产部署账号当前为 wms_zxj，按现场账号授予 SELECT/INSERT/UPDATE/DELETE。

CREATE TABLE IF NOT EXISTS public.ai_provider_config (
    id VARCHAR(64) PRIMARY KEY,
    system_code VARCHAR(32) NOT NULL UNIQUE,
    base_url VARCHAR(500) NOT NULL DEFAULT '',
    model VARCHAR(200) NOT NULL DEFAULT '',
    api_key_ciphertext TEXT,
    api_key_last4 VARCHAR(8),
    enabled SMALLINT NOT NULL DEFAULT 1,
    timeout_seconds INTEGER NOT NULL DEFAULT 30,
    provider VARCHAR(100),
    version INTEGER NOT NULL DEFAULT 1,
    updated_by VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid SMALLINT NOT NULL DEFAULT 1
);

COMMENT ON TABLE public.ai_provider_config IS
    '仓储智能体模型配置；api_key_ciphertext 只存 AES-256-GCM 密文，页面只显示 last4';

-- GRANT SELECT, INSERT, UPDATE, DELETE ON public.ai_provider_config TO wms_zxj;
