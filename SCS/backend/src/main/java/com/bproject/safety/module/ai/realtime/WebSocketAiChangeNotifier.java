package com.bproject.safety.module.ai.realtime;

import com.bproject.safety.common.realtime.LiveEvent;
import com.bproject.safety.common.realtime.LiveEventTypes;
import com.bproject.safety.common.realtime.WebSocketSessionRegistry;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

/**
 * AI 事件变更 → LiveEvent 广播：AiEventService → 本发布器 → WebSocketSessionRegistry。
 * data 只含 aiEventId/status/confidence/type/linkedAlertId 轻量摘要，禁止携带检测框数组与截图。
 */
@Component
public class WebSocketAiChangeNotifier implements AiChangeNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAiChangeNotifier.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final WebSocketSessionRegistry registry;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WebSocketAiChangeNotifier(WebSocketSessionRegistry registry, ObjectMapper objectMapper, Clock clock) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void changed(String op, DemoAiEvent after) {
        if (after == null) {
            return;
        }
        try {
            String type = switch (op) {
                case "new" -> LiveEventTypes.AI_NEW;
                case "reviewed" -> LiveEventTypes.AI_REVIEWED;
                default -> LiveEventTypes.AI_CHANGED;
            };
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("aiEventId", after.id);
            data.put("status", after.status);
            data.put("confidence", after.confidence);
            data.put("type", after.type);
            data.put("changeType", op);
            data.put("area", after.area);
            data.put("camera", after.camera);
            if (after.linkedAlertId != null) {
                data.put("linkedAlertId", after.linkedAlertId);
            }
            LiveEvent event = LiveEvent.of(type, OffsetDateTime.now(clock.withZone(ZONE)).toString(), null, data);
            registry.broadcast(new TextMessage(objectMapper.writeValueAsString(event)));
        } catch (RuntimeException ex) {
            log.warn("publish ai live event failed op={} id={}: {}", op, after.id, ex.getMessage());
        } catch (Exception ex) {
            log.warn("serialize ai live event failed op={} id={}: {}", op, after.id, ex.getMessage());
        }
    }
}
