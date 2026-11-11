package com.bproject.safety.module.publicdata.repository;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/** server profile 收件箱：payload jsonb 落 openGauss；幂等靠主键 ON CONFLICT。 */
@Component
@Profile("server")
public class JdbcPublicInboxRepository implements PublicInboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPublicInboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(String eventId, long committedMs, String wireSha256, JsonNode payload) {
        // openGauss 不支持 INSERT ON CONFLICT：NOT EXISTS 守卫 + 主键双保险；返回受影响行数判定是否新落箱
        int updated = jdbc.update(
                "INSERT INTO safety.public_device_inbox (event_id, committed_ms, wire_sha256, payload) "
                        + "SELECT :eventId, :committedMs, :wireSha256, CAST(:payload AS jsonb) "
                        + "WHERE NOT EXISTS (SELECT 1 FROM safety.public_device_inbox WHERE event_id=:eventId)",
                Map.of("eventId", eventId, "committedMs", committedMs,
                        "wireSha256", wireSha256 == null ? "" : wireSha256,
                        "payload", payload.toString()));
        return updated > 0;
    }

    @Override
    public void markProcessed(String eventId) {
        jdbc.update("UPDATE safety.public_device_inbox SET status='PROCESSED', processed_at=now(), last_error=NULL "
                + "WHERE event_id=:eventId", Map.of("eventId", eventId));
    }

    @Override
    public void markFailed(String eventId, String error) {
        jdbc.update("UPDATE safety.public_device_inbox SET status='FAILED', last_error=:error "
                + "WHERE event_id=:eventId", Map.of("eventId", eventId, "error", truncate(error)));
    }

    @Override
    public String statusOf(String eventId) {
        var rows = jdbc.queryForList("SELECT status FROM safety.public_device_inbox WHERE event_id=:eventId",
                Map.of("eventId", eventId));
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("status"));
    }

    @Override
    public long countByStatus(String status) {
        Long c = jdbc.queryForObject("SELECT count(*) FROM safety.public_device_inbox WHERE status=:status",
                Map.of("status", status), Long.class);
        return c == null ? 0 : c;
    }

    @Override
    public String backfillCursor() {
        var rows = jdbc.queryForList("SELECT state_value FROM safety.public_device_state WHERE state_key=:k",
                Map.of("k", "backfill-cursor"));
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("state_value"));
    }

    @Override
    public void saveBackfillCursor(String cursor) {
        // openGauss 兼容：先 UPDATE 后补插，避免 ON CONFLICT/ON DUPLICATE 语法差异
        jdbc.update("UPDATE safety.public_device_state SET state_value=:v, updated_at=now() WHERE state_key=:k",
                Map.of("k", "backfill-cursor", "v", cursor));
        jdbc.update("INSERT INTO safety.public_device_state (state_key, state_value) "
                + "SELECT :k, :v WHERE NOT EXISTS (SELECT 1 FROM safety.public_device_state WHERE state_key=:k)",
                Map.of("k", "backfill-cursor", "v", cursor));
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }
}
