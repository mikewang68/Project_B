package com.bproject.safety.infrastructure.timeseries;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * openGemini 轻量客户端（独立 Adapter，不引入第三方 SDK）。
 *
 * <p>本阶段只实现连接配置与 /ping 健康检测；设备遥测、定位、距离趋势等时序写入
 * 在后续业务阶段扩展。openGemini 的 /ping 成功返回 2xx（通常 204）。</p>
 */
@Component
@EnableConfigurationProperties(OpenGeminiProperties.class)
public class OpenGeminiClient {
    private final OpenGeminiProperties properties;
    private final HttpClient httpClient;

    public OpenGeminiClient(OpenGeminiProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.timeoutMs()))
                .build();
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    /**
     * 调用 GET {baseUrl}/ping。
     *
     * @return PingResult(是否成功, 版本响应头, 摘要)
     */
    public PingResult ping() {
        if (!properties.enabled() || properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            return PingResult.disabled();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.baseUrl() + "/ping"))
                    .timeout(Duration.ofMillis(properties.timeoutMs()))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            String version = response.headers().firstValue("X-Geminidb-Version").orElse("");
            if (code >= 200 && code < 300) {
                return new PingResult(true, version, "openGemini /ping HTTP " + code);
            }
            return new PingResult(false, version, "openGemini /ping HTTP " + code);
        } catch (Exception ex) {
            return new PingResult(false, "", rootCause(ex));
        }
    }

    private String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    public record PingResult(boolean ok, String version, String detail) {
        static PingResult disabled() {
            return new PingResult(false, "", "openGemini 未启用 (app.opengemini.enabled=false)");
        }
    }
}
