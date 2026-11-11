package com.bproject.safety.module.publicdata.repository;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 非服务器 profile 的内存收件箱（本地开发/测试）。 */
@Component
@Profile("!server")
public class InMemoryPublicInboxRepository implements PublicInboxRepository {

    private final Map<String, Record> inbox = new ConcurrentHashMap<>();
    private volatile String backfillCursor;

    public static final class Record {
        public final long committedMs;
        public final String wireSha256;
        public final JsonNode payload;
        public volatile String status;
        public volatile String lastError;

        Record(long committedMs, String wireSha256, JsonNode payload) {
            this.committedMs = committedMs;
            this.wireSha256 = wireSha256;
            this.payload = payload;
            this.status = "RECEIVED";
        }
    }

    @Override
    public boolean insertIfAbsent(String eventId, long committedMs, String wireSha256, JsonNode payload) {
        return inbox.putIfAbsent(eventId, new Record(committedMs, wireSha256, payload)) == null;
    }

    @Override
    public void markProcessed(String eventId) {
        Record r = inbox.get(eventId);
        if (r != null) {
            r.status = "PROCESSED";
        }
    }

    @Override
    public void markFailed(String eventId, String error) {
        Record r = inbox.get(eventId);
        if (r != null) {
            r.status = "FAILED";
            r.lastError = error;
        }
    }

    @Override
    public String statusOf(String eventId) {
        Record r = inbox.get(eventId);
        return r == null ? null : r.status;
    }

    @Override
    public long countByStatus(String status) {
        return inbox.values().stream().filter(r -> r.status.equals(status)).count();
    }

    @Override
    public String backfillCursor() {
        return backfillCursor;
    }

    @Override
    public void saveBackfillCursor(String cursor) {
        this.backfillCursor = cursor;
    }

    public Record find(String eventId) {
        return inbox.get(eventId);
    }
}
