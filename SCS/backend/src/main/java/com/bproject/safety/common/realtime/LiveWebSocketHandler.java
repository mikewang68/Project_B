package com.bproject.safety.common.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * /ws/live 原生 WebSocket 处理器：
 * 识别客户端每约 20 秒发送的 {"type":"ping"}，回复 {"type":"pong","ts":"..."}；
 * ping 不参与业务广播。其余文本消息当前忽略（事件只由后端向外推送）。
 */
public class LiveWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveWebSocketHandler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final WebSocketSessionRegistry registry;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LiveWebSocketHandler(WebSocketSessionRegistry registry, ObjectMapper objectMapper, Clock clock) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        registry.connect(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        String type = extractType(payload);
        if (LiveEventTypes.PING.equals(type)) {
            Map<String, Object> pong = Map.of(
                    "type", LiveEventTypes.PONG,
                    "ts", OffsetDateTime.now(clock.withZone(ZONE)).toString());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(pong)));
        }
        // 其他客户端消息忽略：实时链路为“服务端推送 + REST 权威读取”
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WS transport error {}: {}", session.getId(), exception.getMessage());
        registry.disconnect(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.disconnect(session);
    }

    private String extractType(String payload) {
        try {
            return objectMapper.readTree(payload).path("type").asText("");
        } catch (Exception ex) {
            return "";
        }
    }
}
