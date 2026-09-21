package com.bproject.safety.module.alert.realtime;

import com.bproject.safety.common.realtime.LiveEvent;
import com.bproject.safety.common.realtime.LiveEventGate;
import com.bproject.safety.common.realtime.LiveEventTypes;
import com.bproject.safety.common.realtime.WebSocketSessionRegistry;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.service.AlertChangeNotifier;
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
 * 告警变更 → LiveEvent 广播：AlertService → 本发布器 → WebSocketSessionRegistry。
 * 广播代码不散落在 Controller；未来替换为 RocketMQ 时只需更换本实现。
 */
@Component
public class WebSocketAlertChangeNotifier implements AlertChangeNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAlertChangeNotifier.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final WebSocketSessionRegistry registry;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final LiveEventGate gate;

    public WebSocketAlertChangeNotifier(WebSocketSessionRegistry registry, ObjectMapper objectMapper, Clock clock,
                                        LiveEventGate gate) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.gate = gate;
    }

    @Override
    public void changed(String op, DemoAlert after) {
        if (after == null) {
            return;
        }
        // Phase B：跨聚合 workflow 缓冲期间延迟到所有 save 成功后发送。
        gate.emit(() -> {
            try {
                String type = mapType(op);
                // 只发送轻量摘要，禁止携带完整 evidence / timeline / 详情
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("alertId", after.id);
                // 中文展示标签（兼容现有前端），同时下发机器 code
                data.put("status", after.getStatus());
                data.put("statusCode", after.statusCode);
                data.put("level", after.getRisk());
                data.put("risk", after.getRisk());
                data.put("riskCode", after.riskCode);
                data.put("changeType", op);
                data.put("title", after.title);
                data.put("area", after.area);
                data.put("assignee", after.assignee);
                if (after.mobileStage != null) {
                    data.put("mobileStage", after.mobileStage);
                }
                LiveEvent event = LiveEvent.of(type, OffsetDateTime.now(clock.withZone(ZONE)).toString(), null, data);
                registry.broadcast(new TextMessage(objectMapper.writeValueAsString(event)));
            } catch (RuntimeException ex) {
                // 广播失败只记录，不影响业务写操作
                log.warn("publish alert live event failed op={} id={}: {}", op, after.id, ex.getMessage());
            } catch (Exception ex) {
                log.warn("serialize alert live event failed op={} id={}: {}", op, after.id, ex.getMessage());
            }
        });
    }

    /** 后端内部操作名 → 对外 LiveEvent.type */
    private String mapType(String op) {
        return switch (op) {
            case "new" -> LiveEventTypes.ALERT_NEW;
            case "assign" -> LiveEventTypes.ALERT_ASSIGNED;
            case "treatment" -> LiveEventTypes.ALERT_TREATMENT;
            case "review" -> LiveEventTypes.ALERT_CLOSED;
            case "escalate" -> LiveEventTypes.ALERT_ESCALATED;
            case "linkage" -> LiveEventTypes.ALERT_LINKAGE_CHANGED;
            default -> LiveEventTypes.ALERT_CHANGED;
        };
    }
}
