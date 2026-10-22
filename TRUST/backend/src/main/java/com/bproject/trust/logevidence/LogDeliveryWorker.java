package com.bproject.trust.logevidence;

import com.bproject.trust.shared.json.Json;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LogDeliveryWorker {
  private final com.bproject.trust.shared.BoundedWork executor =
      new com.bproject.trust.shared.BoundedWork("trust-log-delivery", 1);
  private final ConcurrentHashMap<String, String> active = new ConcurrentHashMap<>();

  private static final class DeliveryFailure extends RuntimeException {
    final String code;
    final boolean terminal;

    DeliveryFailure(String code, boolean terminal) {
      this.code = code;
      this.terminal = terminal;
    }
  }

  private final JdbcTemplate db;
  private final LogSettings settings;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public LogDeliveryWorker(JdbcTemplate db, LogSettings settings) {
    this.db = db;
    this.settings = settings;
  }

  @Scheduled(fixedDelayString = "${trust.logs.delivery-interval-ms:5000}")
  public void dispatch() {
    executor.batch(this::poll);
  }

  @jakarta.annotation.PreDestroy
  void close() {
    executor.close();
  }

  @Scheduled(fixedDelay = 15000, scheduler = "leaseScheduler")
  public void renew() {
    active.forEach(
        (id, lease) -> {
          try {
            db.update(
                "UPDATE audit_delivery_read SET lease_until=CURRENT_TIMESTAMP+INTERVAL '60 seconds'"
                    + " WHERE id=? AND lease_token=? AND state='PENDING' AND"
                    + " lease_until>clock_timestamp()",
                id,
                lease);
          } catch (Exception unavailable) {
            com.bproject.trust.shared.WorkerSignals.failure(
                "LogDeliveryWorker",
                "renew",
                unavailable); /* Stale workers cannot confirm delivery. */
          }
        });
  }

  private boolean owned(String id, String lease) {
    if (executor.closing()) return false;
    return db.queryForObject(
            "SELECT COUNT(*) FROM audit_delivery_read WHERE id=? AND lease_token=? AND"
                + " state='PENDING' AND lease_until>clock_timestamp()",
            Long.class,
            id,
            lease)
        == 1;
  }

  public void poll() {
    if (executor.closing()) return;
    try {
      var cfg = settings.read().delivery();
      if (!cfg.enabled()) return;
      String token = Files.readString(Path.of(cfg.tokenFile())).trim();
      if (token.length() < 32 || token.length() > 512) return;
      for (var row :
          db.queryForList(
              "SELECT * FROM audit_delivery_read WHERE state='PENDING' AND"
                  + " next_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR"
                  + " lease_until<CURRENT_TIMESTAMP) AND org_id<>'SIMULATED_LOCAL' ORDER BY"
                  + " created_at LIMIT 20")) {
        String id = (String) row.get("id");
        if (executor.closing()) return;
        String lease = UUID.randomUUID().toString();
        // Atomic deadline acts as a recoverable lease; duplicate delivery is safe by stable event
        // ID.
        if (db.update(
                "UPDATE audit_delivery_read SET"
                    + " lease_token=?,lease_until=CURRENT_TIMESTAMP+INTERVAL '60 seconds' WHERE"
                    + " id=? AND state='PENDING' AND next_at<=CURRENT_TIMESTAMP AND (lease_until IS"
                    + " NULL OR lease_until<CURRENT_TIMESTAMP)",
                lease,
                id)
            != 1) continue;
        active.put(id, lease);
        try {
          String json = (String) row.get("event_json");
          if (json == null) {
            var scope = cfg.orgScopes().get((String) row.get("org_id"));
            if (scope == null || !"TRUST".equals(scope.appCode()))
              throw new IllegalArgumentException();
            String actor = Objects.toString(row.get("actor"), "");
            String kind = (String) row.get("source_kind");
            String assurance =
                actor.isEmpty()
                    ? "ANONYMOUS"
                    : actor.startsWith("iam:") || Set.of("CLIENT", "REQUEST").contains(kind)
                        ? "AUTHENTICATED"
                        : "SYSTEM";
            if (actor.startsWith("iam:")) actor = actor.substring(4);
            var event =
                new LogEvent(
                    1,
                    id,
                    Instant.parse((String) row.get("occurred_at")),
                    scope.appCode(),
                    scope.domainId(),
                    scope.zoneId(),
                    actor,
                    assurance,
                    (String) row.get("menu_id"),
                    (String) row.get("action"),
                    (String) row.get("object_id"),
                    (String) row.get("outcome"),
                    (String) row.get("method"),
                    (String) row.get("path"),
                    (String) row.get("trace_id"),
                    0,
                    (String) row.get("source_kind"),
                    (String) row.get("summary"));
            json = LogInput.canonical(event);
            db.update(
                "UPDATE audit_delivery_read SET event_json=? WHERE id=? AND event_json IS NULL AND"
                    + " lease_token=? AND lease_until>clock_timestamp()",
                json,
                id,
                lease);
            json =
                db.queryForObject(
                    "SELECT event_json FROM audit_delivery_read WHERE id=?", String.class, id);
          }
          if (!owned(id, lease)) continue;
          var response =
              receive(
                  HttpRequest.newBuilder(URI.create(cfg.url()))
                      .timeout(Duration.ofSeconds(5))
                      .header("Authorization", "Bearer " + token)
                      .header("Content-Type", "application/json")
                      .POST(HttpRequest.BodyPublishers.ofString(json))
                      .build());
          byte[] body = response.body();
          if (!owned(id, lease)) continue;
          if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new DeliveryFailure(
                "AUDIT_HTTP_" + response.statusCode(),
                response.statusCode() >= 400
                    && response.statusCode() < 500
                    && response.statusCode() != 429);
          if (body.length > 65536) throw new DeliveryFailure("AUDIT_INVALID_RECEIPT", true);
          var receipt = Json.MAPPER.readTree(body);
          if (!id.equals(receipt.path("eventId").asText())
              || !"TRUST".equals(receipt.path("appCode").asText())
              || !receipt.path("stored").asBoolean()
              || !LogInput.hash(json).equals(receipt.path("payloadHash").asText()))
            throw new DeliveryFailure("AUDIT_INVALID_RECEIPT", true);
          db.update(
              "UPDATE audit_delivery_read SET"
                  + " state='DELIVERED',last_error=NULL,lease_token=NULL,lease_until=NULL WHERE"
                  + " id=? AND lease_token=? AND lease_until>clock_timestamp()",
              id,
              lease);
        } catch (Exception e) {
          if (e instanceof InterruptedException) Thread.currentThread().interrupt();
          // Retain durable events indefinitely. Configuration repair recovers without regenerating
          // IDs.
          int attempts = ((Number) row.get("attempts")).intValue() + 1;
          boolean unconfigured = e instanceof IllegalArgumentException;
          boolean terminal =
              !unconfigured
                  && (attempts >= 30 || e instanceof DeliveryFailure failure && failure.terminal);
          String code =
              unconfigured
                  ? "AUDIT_SCOPE_NOT_CONFIGURED"
                  : e instanceof DeliveryFailure failure
                      ? failure.code
                      : "AUDIT_DELIVERY_UNCONFIRMED";
          db.update(
              "UPDATE audit_delivery_read SET"
                  + " state=?,attempts=?,last_error=?,next_at=CURRENT_TIMESTAMP+INTERVAL '60"
                  + " seconds',lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=? AND"
                  + " lease_until>clock_timestamp()",
              terminal ? "FAILED" : "PENDING",
              attempts,
              code,
              id,
              lease);
        } finally {
          active.remove(id, lease);
        }
      }
    } catch (Exception ignored) {
      com.bproject.trust.shared.WorkerSignals.failure("LogDeliveryWorker", "persistence", ignored);
      /* Local collection is independent from remote delivery availability. */
    }
  }

  // The deadline includes the entire body; an InputStream body handler completes at headers.
  // Cancel the exchange on timeout, and cancel the subscription before buffering beyond 64 KiB.
  HttpResponse<byte[]> receive(HttpRequest request) throws Exception {
    var pending = http.sendAsync(request, ignored -> new ReceiptSubscriber());
    try {
      return pending.get(5, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof DeliveryFailure failure) throw failure;
      throw e;
    } finally {
      if (!pending.isDone()) pending.cancel(true);
    }
  }

  private static final class ReceiptSubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
      subscription = value;
      value.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > 65536 - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(new DeliveryFailure("AUDIT_INVALID_RECEIPT", true));
          return;
        }
        byte[] part = new byte[buffer.remaining()];
        buffer.get(part);
        bytes.writeBytes(part);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
