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
    @org.springframework.beans.factory.annotation.Autowired private LiveSessionAuthorization authorization;
    private final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();

    /** 复查-D7：慢客户端防护参数——发送阻塞超 2s 或缓冲超 256KB 即关闭该连接，不拖住业务提交线程。 */
    private static final int SEND_TIME_LIMIT_MS = 2000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 256 * 1024;

    public void connect(WebSocketSession session) {
        // ConcurrentWebSocketSessionDecorator：并发发送安全 + 超时/缓冲上限（超限抛 SessionLimitExceeded 走 disconnect）
        sessions.put(session.getId(), new org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES));
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
        String type;
        try{type=json.readTree(message.getPayload()).path("type").asText();}catch(Exception invalid){return;}
        for(var session:sessions.values())try{
            var actor=authorization==null?null:authorization.current(session);
            if(authorization==null || authorization.permits(actor,type))send(session,message);
        }catch(Exception revoked){closeUnauthorized(session);}
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=5000)
    public void sweepRevoked(){
        if(authorization==null)return;
        for(var session:sessions.values())try{authorization.current(session);}catch(Exception revoked){closeUnauthorized(session);}
    }
    private void closeUnauthorized(WebSocketSession session){
        sessions.remove(session.getId());
        try{session.close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);}catch(Exception ignored){}
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
