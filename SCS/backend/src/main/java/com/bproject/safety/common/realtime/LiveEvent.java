package com.bproject.safety.common.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * 轻量统一实时事件结构：{ type, eventId, ts, traceId, data }。
 *
 * <p>data 只放 alertId/status/level 等摘要字段，禁止携带完整 Evidence / Timeline /
 * 详情 / 截图 Base64；客户端收到后通过 REST 重新拉取权威数据。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LiveEvent(String type, String eventId, String ts, String traceId, Map<String, Object> data) {

    public static LiveEvent of(String type, String ts, String traceId, Map<String, Object> data) {
        return new LiveEvent(type, java.util.UUID.randomUUID().toString(), ts, traceId, data);
    }
}
