package com.bproject.safety.module.publicdata.repository;

import com.fasterxml.jackson.databind.JsonNode;

/** 公共底座事件接收箱：event_id 幂等，业务结果与收箱记录同事务推进。 */
public interface PublicInboxRepository {

    /** 幂等落箱：已存在返回 false（调用方直接 ACK，不再重复映射）。payload 序列化为 jsonb。 */
    boolean insertIfAbsent(String eventId, long committedMs, String wireSha256, JsonNode payload);

    void markProcessed(String eventId);

    void markFailed(String eventId, String error);

    /** 当前状态（RECEIVED/PROCESSED/FAILED），不存在返回 null。 */
    String statusOf(String eventId);

    long countByStatus(String status);

    String backfillCursor();

    void saveBackfillCursor(String cursor);
}
