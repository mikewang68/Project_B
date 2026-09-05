package com.bproject.safety.common.realtime;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * WebSocket 在线会话注册表（单实例 Demo）。
 *
 * <p>node4 / node5 多实例下的跨节点广播后续通过 RocketMQ 解决，本阶段只做单实例。
 * 所有发送均为 best-effort：发送失败只记录日志，不影响业务写操作。</p>
 */
@Component
public class WebSocketSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionRegistry.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void connect(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.debug("WS connected: {} (online={})", session.getId(), sessions.size());
    }

    public void disconnect(WebSocketSession session) {
        sessions.remove(session.getId());
        log.debug("WS disconnected: {} (online={})", session.getId(), sessions.size());
    }

    public int onlineCount() {
        return sessions.size();
    }

    /** 向全部在线连接广播文本消息；单个连接失败不影响其他连接。 */
    public void broadcast(TextMessage message) {
        sessions.values().forEach(session -> send(session, message));
    }

    private void send(WebSocketSession session, TextMessage message) {
        if (!session.isOpen()) {
            sessions.remove(session.getId());
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(message);
            }
        } catch (IOException | RuntimeException ex) {
            log.warn("WS broadcast failed to {}: {}", session.getId(), ex.getMessage());
            sessions.remove(session.getId());
        }
    }
}
