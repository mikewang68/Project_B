package com.bproject.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Configurable log-specific port. Not wired to existing warehouse event APIs. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="audit.chain.mode",havingValue="legacy")
public class ChainWorker {
    private final LogStore store;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String base, token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public ChainWorker(LogStore store, ObjectMapper mapper, @Value("${audit.chain.enabled:false}") boolean enabled,
                       @Value("${audit.chain.base-url:}") String base, @Value("${audit.chain.token-file:}") String file) throws Exception {
        this.store = store; this.mapper = mapper; this.enabled = enabled; this.base = base.replaceAll("/+$", "");
        this.token = enabled ? Files.readString(Path.of(file)).trim() : "";
        if (enabled && (!java.util.Set.of("http", "https").contains(URI.create(base).getScheme()) || URI.create(base).getUserInfo()!=null || token.length()<32))
            throw new IllegalArgumentException("Invalid chain adapter configuration");
    }
    @Scheduled(fixedDelayString="${audit.chain.interval-ms:5000}")
    public void runBatch() {
        if (!enabled) return;
        // Once configured, pending historical records are eligible without rewriting their event payload.
        store.db.update("UPDATE log_chain SET state='PENDING' WHERE state='NOT_CONFIGURED'");
        var rows = store.db.queryForList("SELECT c.id,c.state,c.attempts,e.event_id,e.payload,e.payload_hash FROM log_chain c JOIN log_event e ON e.id=c.id WHERE c.state IN ('PENDING','ACCEPTED','SENDING') AND c.next_attempt<=? ORDER BY e.received_at LIMIT 20", Timestamp.from(Instant.now()));
        for (var row : rows) {
            String id = (String) row.get("id"), lease = UUID.randomUUID().toString();
            if (store.db.update("UPDATE log_chain SET lease_id=?,state='SENDING',next_attempt=? WHERE id=? AND state IN ('PENDING','ACCEPTED','SENDING') AND next_attempt<=?", lease, Timestamp.from(Instant.now().plusSeconds(60)), id, Timestamp.from(Instant.now())) != 1) continue;
            int attempts = ((Number) row.get("attempts")).intValue() + 1;
            try {
                // POST is idempotent by storage key. Accepted and timeout attempts are reconciled via GET.
                String encoded = URLEncoder.encode(id, StandardCharsets.UTF_8);
                HttpResponse<String> response = http.send(request(base + "/" + encoded).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 404) {
                    String body = mapper.writeValueAsString(Map.of("eventKey", id, "eventId", row.get("event_id"), "payloadHash", row.get("payload_hash"), "event", mapper.readTree((String) row.get("payload"))));
                    response = http.send(request(base).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                }
                if (response.statusCode()<200 || response.statusCode()>=300) {
                    fail(id, lease, attempts, "HTTP_" + response.statusCode(), response.statusCode()>=400 && response.statusCode()<500 && response.statusCode()!=429); continue;
                }
                var body = mapper.readTree(response.body());
                if (!id.equals(body.path("eventKey").asText()) || !row.get("payload_hash").equals(body.path("payloadHash").asText())) throw new IllegalStateException("Receipt mismatch");
                String state = body.path("state").asText(), transaction = body.path("transactionId").asText("");
                if (!java.util.Set.of("ACCEPTED","PENDING","COMMITTED","FAILED").contains(state)) throw new IllegalStateException("Invalid chain state");
                if (state.equals("COMMITTED") && (transaction.isBlank() || transaction.length()>192)) throw new IllegalStateException("Missing commit receipt");
                if (state.equals("FAILED")) { fail(id,lease,attempts,"CHAIN_REJECTED",true); continue; }
                // Polling a durably accepted transaction is not a delivery failure. Reset failure counter.
                store.db.update("UPDATE log_chain SET state=?,attempts=0,transaction_id=?,lease_id=NULL,last_error=NULL,next_attempt=? WHERE id=? AND lease_id=?",
                        state.equals("COMMITTED") ? "COMMITTED" : "ACCEPTED", transaction, Timestamp.from(Instant.now().plusSeconds(5)), id, lease);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                fail(id, lease, attempts, "CHAIN_UNAVAILABLE_OR_INVALID_RECEIPT", false);
            }
        }
    }
    private HttpRequest.Builder request(String url) { return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token); }
    private void fail(String id, String lease, int attempts, String code, boolean permanent) {
        store.db.update("UPDATE log_chain SET state=?,attempts=?,last_error=?,lease_id=NULL,next_attempt=? WHERE id=? AND lease_id=?",
                permanent || attempts >= 30 ? "FAILED" : "PENDING", attempts, code, Timestamp.from(Instant.now().plusSeconds(Math.min(300, 1L << Math.min(attempts,8)))), id, lease);
    }
}
