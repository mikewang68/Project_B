package com.bproject.safety.module.publicdata.service;

import com.bproject.safety.module.publicdata.PublicDataProperties;
import com.bproject.safety.module.publicdata.repository.PublicInboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP 历史补读：底座较早历史不走 MQ 分发，按 (committed_ms,event_id) 游标分页补入同一收件箱
 * （event_id 幂等，与 MQ 消费互不冲突）。每轮最多一页，has_more=false 停止；网络不可达优雅跳过。
 */
@Component
@ConditionalOnProperty(prefix = "app.public-data", name = "backfill-enabled", havingValue = "true")
public class PublicHistoryBackfill implements PublicBackfillInvoker {

    private static final Logger log = LoggerFactory.getLogger(PublicHistoryBackfill.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PublicDataProperties properties;
    private final PublicInboxRepository inbox;
    private final PublicInboxProcessor processor;
    private final HttpClient httpClient = HttpClient.newBuilder()
            // HTTP/1.1：Java 默认 h2c 升级在部分网关下会丢请求体（见 EdgeAiInferenceClient 同注）
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(1500))
            .build();

    public PublicHistoryBackfill(PublicDataProperties properties, PublicInboxRepository inbox,
                                 PublicInboxProcessor processor) {
        this.properties = properties;
        this.inbox = inbox;
        this.processor = processor;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void backfillOnePage() {
        try {
            int received = readOnePage();
            if (received > 0) {
                log.info("公共历史补读本轮落箱 {} 条", received);
            }
        } catch (Exception e) {
            log.debug("公共历史补读暂不可达: {}", e.getMessage());
        }
    }

    /** 手动触发入口（status 端点联动），返回本轮落箱数。 */
    public int backfillNow() throws Exception {
        return readOnePage();
    }

    private int readOnePage() throws Exception {
        String base = properties.httpBaseUrl().replaceAll("/$", "");
        String cursor = inbox.backfillCursor();
        String url = base + "/api/device-data/v1/history?limit=" + properties.backfillPageSize()
                + (cursor == null || cursor.isBlank() ? "" : "&cursor=" + cursor);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + properties.httpToken())
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.debug("公共历史补读返回 {}: {}", response.statusCode(), response.body().substring(0, Math.min(120, response.body().length())));
            return 0;
        }
        JsonNode body = JSON.readTree(response.body());
        List<JsonNode> events = new ArrayList<>();
        for (JsonNode item : body.path("events")) {
            if (item.isObject()) {
                events.add(item);
            }
        }
        int inserted = 0;
        for (JsonNode item : events) {
            String eventId = item.path("event_id").asText();
            if (eventId.isBlank()) {
                continue;
            }
            processor.ensureInbox(eventId,
                    item.path("committed_ms").asLong(0),
                    item.path("wire_sha256").asText(null),
                    item.path("event"));
            processor.processMapped(eventId, item);
            inserted++;
        }
        String nextCursor = body.path("next_cursor").asText(null);
        boolean hasMore = body.path("has_more").asBoolean(false);
        if (nextCursor != null && !nextCursor.isBlank()) {
            inbox.saveBackfillCursor(nextCursor);
        }
        if (!hasMore && !events.isEmpty()) {
            log.info("公共历史补读已到尾部（本轮 {} 条）", events.size());
        }
        return inserted;
    }
}
