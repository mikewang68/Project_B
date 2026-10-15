package com.bproject.safety.common.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册原生 WebSocket 端点 /ws/live（本地 ws://localhost:8080/ws/live；
 * 服务器由 Easegress / Nginx 处理 Upgrade，本阶段不改服务器配置）。
 * 不使用 STOMP / SockJS。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WebSocketSessionRegistry registry;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String[] allowedOrigins;

    public WebSocketConfig(
            WebSocketSessionRegistry registry,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${app.cors.allowed-origins}") String allowedOrigins) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry handlerRegistry) {
        handlerRegistry
                .addHandler(new LiveWebSocketHandler(registry, objectMapper, clock), "/ws/live")
                .setAllowedOriginPatterns(allowedOrigins);
    }
}
