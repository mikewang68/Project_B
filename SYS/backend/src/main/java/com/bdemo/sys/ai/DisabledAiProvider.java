package com.bdemo.sys.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 默认（兜底）实现：不启用任何外部 AI，规则结果原样返回。
 * 当 app.ai.provider 缺失或为 disabled 时生效。
 */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "provider",
        havingValue = "disabled", matchIfMissing = true)
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
    public Map<String, Object> enhance(Map<String, Object> context) {
        return context;
    }
}
