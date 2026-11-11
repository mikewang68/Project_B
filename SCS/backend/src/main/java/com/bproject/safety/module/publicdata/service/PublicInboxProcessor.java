package com.bproject.safety.module.publicdata.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 收件箱处理两段式：
 * ① ensureInbox 以 REQUIRES_NEW 独立提交落箱（幂等），保证映射失败时原事件仍留收件箱可补偿；
 * ② processMapped 在同一事务里做状态检查 + 业务映射 + 状态推进（业务结果与进度同事务）。
 * MQ 重复投递/补读重复时按收件箱状态收敛：PROCESSED 跳过，RECEIVED/FAILED 重跑映射（补偿）。
 */
public interface PublicInboxProcessor {

    /** @return true=新落箱；false=已存在（按状态决定补偿重跑或跳过） */
    boolean ensureInbox(String eventId, long committedMs, String wireSha256, JsonNode payload);

    void processMapped(String eventId, JsonNode envelope);

    void markFailed(String eventId, String error);
}
