package com.bproject.safety.common.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

/**
 * 领域实时事件通用发布器：人员 / 围栏 / 防碰撞等感知模块共用，避免每个模块各写一套 WS 广播。
 *
 * <p>data 只放 id / 状态 / 风险等轻量摘要，禁止携带完整详情；客户端收到后按 REST 重新拉取。
 * 广播为 best-effort：序列化或发送失败只记录日志，不影响业务写操作结果。</p>
 */
@Component
public class DomainLivePublisher {

    private static final Logger log = LoggerFactory.getLogger(DomainLivePublisher.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final WebSocketSessionRegistry registry;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final LiveEventGate gate;

    public DomainLivePublisher(WebSocketSessionRegistry registry, ObjectMapper objectMapper, Clock clock,
                               LiveEventGate gate) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.gate = gate;
    }

    public void publish(String type, Map<String, Object> data) {
        // Phase B：跨聚合 workflow 缓冲期间只入队，所有 save 成功后由 LiveEventGate.flush 统一发送。
        gate.emit(() -> {
            try {
                String ts = OffsetDateTime.now(clock.withZone(ZONE)).toString();
                LiveEvent event = LiveEvent.of(type, ts, null, data);
                registry.broadcast(new TextMessage(objectMapper.writeValueAsString(event)));
            } catch (Exception ex) {
                log.warn("publish domain live event failed type={}: {}", type, ex.getMessage());
            }
        });
    }
}
