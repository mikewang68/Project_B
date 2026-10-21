package com.bproject.trust.logevidence;

import com.bproject.trust.ingress.*;
import com.bproject.trust.shared.json.Json;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.bproject.trust.shared.BoundedWork;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LogArchiveWorker {
  private final LogSettings settings;
  private final LogEvidenceService service;
  private final BoundedWork executor = new BoundedWork("trust-log-archive", 1);
  private final ConcurrentHashMap<String, String> active = new ConcurrentHashMap<>();

  public LogArchiveWorker(LogSettings settings, LogEvidenceService service) {
    this.settings = settings;
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${trust.logs.interval-ms:5000}")
  public void dispatch() {
    executor.batch(this::poll);
  }

  @jakarta.annotation.PreDestroy
  void close() { executor.close(); }

  @Scheduled(fixedDelay = 15000, scheduler = "leaseScheduler")
  public void renew() {
    active.forEach((key, lease) -> {
      try {
        service.db.update("UPDATE audit_evidence SET lease_until=CURRENT_TIMESTAMP+INTERVAL '60 seconds'"
            + " WHERE event_key=? AND lease_token=? AND lease_until>clock_timestamp()", key, lease);
      } catch (Exception unavailable) { /* Expired claims cannot advance. */ }
    });
  }

  public void poll() {
    if (executor.closing()) return;
    try {
      var c = settings.read();
      if (!c.enabled()) return;
      var control = service.db.queryForMap("SELECT * FROM audit_log_settings WHERE id=1");
      boolean paused = Boolean.TRUE.equals(control.get("archive_paused"));
      boolean chainPaused = Boolean.TRUE.equals(control.get("chain_paused"));
      for (var row :
          service.db.queryForList(
              "SELECT * FROM audit_evidence WHERE chain_event_id IS NULL AND"
                  + " archive_state<>'FAILED' AND next_at<=CURRENT_TIMESTAMP AND (lease_until IS"
                  + " NULL OR lease_until<CURRENT_TIMESTAMP)"
                  + " AND (?=FALSE OR archive_state='STORED')"
                  + " AND (?=FALSE OR archive_state<>'STORED') ORDER BY created_at LIMIT 10",
              paused,
              chainPaused)) {
        String key = (String) row.get("event_key"), lease = UUID.randomUUID().toString();
        if (executor.closing()) return;
        if (service.db.update(
                "UPDATE audit_evidence SET lease_token=?,lease_until=CURRENT_TIMESTAMP+INTERVAL '60"
                    + " seconds' WHERE event_key=? AND (lease_until IS NULL OR"
                    + " lease_until<CURRENT_TIMESTAMP)",
                lease,
                key)
            != 1) continue;
        active.put(key, lease);
        try {
          process(row, lease, c);
        } catch (Exception e) {
          int attempts = ((Number) row.get("attempts")).intValue() + 1;
          String code = e instanceof IngressError x ? x.code : "ARCHIVE_DEPENDENCY_UNAVAILABLE";
          service.db.update(
              "UPDATE audit_evidence SET attempts=?,archive_state=CASE WHEN ? >= 10 THEN 'FAILED'"
                  + " ELSE archive_state END,last_error=?,next_at=CURRENT_TIMESTAMP+INTERVAL '30"
                  + " seconds',lease_token=NULL,lease_until=NULL WHERE event_key=? AND"
                  + " lease_token=? AND lease_until>clock_timestamp()",
              attempts,
              attempts,
              code,
              key,
              lease);
        } finally {
          active.remove(key, lease);
        }
      }
    } catch (Exception ignored) {
      /* Durable rows remain recoverable; no configuration means no network calls. */
    }
  }

  void process(Map<String, Object> row, String lease, LogSettings.Config config) throws Exception {
    String key = (String) row.get("event_key");
    if (!owned(key, lease)) return;
    var source =
        config.sources().stream()
            .filter(s -> s.id().equals(row.get("source_id")))
            .findFirst()
            .orElseThrow(LogEvidenceService::missing);
    service.checkOwner(source, row);
    if (row.get("archive_cid") == null) {
      String encrypted = (String) row.get("ciphertext");
      if (!LogInput.hash(encrypted).equals(row.get("ciphertext_hash")))
        throw new IngressError(409, "ARCHIVE_INTEGRITY", "归档密文摘要不一致");
      // Re-check just before network dispatch. An already in-flight add can finish after pause.
      if (Boolean.TRUE.equals(
          service.db.queryForObject(
              "SELECT archive_paused FROM audit_log_settings WHERE id=1", Boolean.class))) {
        release(key, lease);
        return;
      }
      if (!owned(key, lease)) return;
      String cid = service.storage.add(encrypted.getBytes(StandardCharsets.UTF_8));
      if (!owned(key, lease)) return;
      if (service.db.update(
              "UPDATE audit_evidence SET archive_cid=?,archive_state='STORED',last_error=NULL WHERE"
                  + " event_key=? AND lease_token=? AND lease_until>clock_timestamp()",
              cid,
              key,
              lease)
          != 1) return;
      row.put("archive_cid", cid);
    }
    if (Boolean.TRUE.equals(
        service.db.queryForObject(
            "SELECT chain_paused FROM audit_log_settings WHERE id=1", Boolean.class))) {
      release(key, lease);
      return;
    }
    service.tx.executeWithoutResult(
        t -> {
          var locked =
              service.db.queryForMap(
                  "SELECT * FROM audit_evidence WHERE event_key=? FOR UPDATE", key);
          if (!lease.equals(locked.get("lease_token")) || !owned(key, lease)) return;
          var projection = service.projectionSource(row);
          projection.type("AUDIT_LOG");
          Map<String, Object> payload =
              Map.of(
                  "eventKey",
                  key,
                  "payloadHash",
                  row.get("payload_hash"),
                  "ciphertextCid",
                  row.get("archive_cid"),
                  "ciphertextSha256",
                  row.get("ciphertext_hash"),
                  "keyId",
                  row.get("key_id"));
          // Only the encrypted archive index enters the existing generic archive/ledger path.
          // Projection time follows the generic microsecond contract; original nanos remain
          // authenticated by payloadHash.
          String projectionTime =
              Instant.parse((String) row.get("occurred_at"))
                  .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                  .toString();
          String request =
              Json.write(
                  Map.of(
                      "schemaVersion",
                      1,
                      "sourceEventId",
                      key,
                      "eventType",
                      "AUDIT_LOG",
                      "businessObjectId",
                      key,
                      "occurredAt",
                      projectionTime,
                      "payload",
                      payload));
          var result =
              service.generic.submit(
                  projection, GenericEventInput.parse(request.getBytes(StandardCharsets.UTF_8)));
          int completed = service.db.update(
              "UPDATE audit_evidence SET"
                  + " chain_event_id=?,lease_token=NULL,lease_until=NULL,last_error=NULL WHERE"
                  + " event_key=? AND lease_token=? AND lease_until>clock_timestamp()",
              result.receipt().get("id"),
              key,
              lease);
          if (completed != 1) throw new IllegalStateException("Archive lease lost before projection confirmation");
        });
  }

  private boolean owned(String key, String lease) {
    if (executor.closing()) return false;
    return service.db.queryForObject("SELECT COUNT(*) FROM audit_evidence WHERE event_key=?"
        + " AND lease_token=? AND lease_until>clock_timestamp()", Long.class, key, lease) == 1;
  }

  private void release(String key, String lease) {
    service.db.update(
        "UPDATE audit_evidence SET lease_token=NULL,lease_until=NULL WHERE event_key=? AND"
            + " lease_token=?",
        key,
        lease);
  }
}
