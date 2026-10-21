package com.bproject.audit.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/** One batch per invocation. Schedule from the owning application; never send from browser. */
public class LogDispatcher {
    private final JdbcLogOutbox outbox;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public LogDispatcher(JdbcLogOutbox outbox, ObjectMapper mapper, String endpoint, String token) {
        this.outbox = outbox; this.mapper = mapper; this.endpoint = URI.create(endpoint); this.token = token;
        if (!java.util.Set.of("http", "https").contains(this.endpoint.getScheme()) || this.endpoint.getUserInfo() != null || token.length() < 32)
            throw new IllegalArgumentException("Invalid log endpoint/credential");
    }
    public void dispatch() {
        for (var row : outbox.due()) {
            String id = (String) row.get("id"), lease = UUID.randomUUID().toString();
            if (!outbox.claim(id, lease)) continue;
            int attempts = ((Number) row.get("attempts")).intValue() + 1;
            try {
                var response = http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString((String) row.get("payload"))).build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    var body = mapper.readTree(response.body());
                    if (!body.path("eventId").asText().equals(row.get("event_id")) || !body.path("stored").asBoolean()
                            || !body.path("payloadHash").asText().equals(row.get("payload_hash"))) throw new IllegalStateException("Invalid durable receipt");
                    outbox.acknowledge(id, lease, response.body());
                } else outbox.failed(id, lease, attempts, "HTTP_" + response.statusCode(), response.statusCode() >= 400 && response.statusCode() < 500 && response.statusCode() != 429);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                outbox.failed(id, lease, attempts, "DELIVERY_UNAVAILABLE", false);
            }
        }
    }
}
