package com.bproject.safety.infrastructure.timeseries;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * openGemini 连接配置，地址只来自环境变量 OPENGEMINI_URL。
 *
 * @param enabled   是否启用（dev/test 默认关闭）
 * @param baseUrl   openGemini 地址，如 http://10.0.0.4:8086
 * @param timeoutMs /ping 超时（毫秒）
 */
@ConfigurationProperties(prefix = "app.opengemini")
public record OpenGeminiProperties(
        boolean enabled,
        String baseUrl,
        long timeoutMs) {

    public OpenGeminiProperties {
        if (timeoutMs <= 0) {
            timeoutMs = 2000;
        }
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }
}
