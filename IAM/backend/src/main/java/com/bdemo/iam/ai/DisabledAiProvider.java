package com.bdemo.iam.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 禁用模式（默认）：不调用任何外部模型，enhance 原样返回。
 * 未配置 app.ai.provider 时默认装配本实现；未来加 DeepSeek 时用 provider=deepseek 切换。
 */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "provider", havingValue = "disabled", matchIfMissing = true)
public class DisabledAiProvider implements AiProvider {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public String name() {
        return "disabled";
    }

    @Override
    public String enhance(String context) {
        return context;
    }
}
