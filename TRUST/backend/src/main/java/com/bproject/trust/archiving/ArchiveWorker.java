package com.bproject.trust.archiving;

import com.bproject.trust.events.EventService;
import com.bproject.trust.evidence.EvidenceService;
import com.bproject.trust.ports.EvidenceStorage;
import com.bproject.trust.ports.LedgerGateway;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ArchiveWorker {
  final JdbcTemplate db;
  final EventService events;
  final EvidenceService evidence;
  final EvidenceStorage ipfs;
  final LedgerGateway fabric;
  final TransactionTemplate tx;
  final ExecutorService executor = Executors.newFixedThreadPool(4);
  final ConcurrentHashMap<String, String> active = new ConcurrentHashMap<>();

  public ArchiveWorker(
      JdbcTemplate db,
      EventService events,
      EvidenceService evidence,
      EvidenceStorage ipfs,
      LedgerGateway fabric,
      TransactionTemplate tx) {
    this.db = db;
    this.events = events;
    this.evidence = evidence;
    this.ipfs = ipfs;
    this.fabric = fabric;
    this.tx = tx;
  }

  @Scheduled(fixedDelay = 1000)
  public void poll() {
    try {
      int slots = 4 - active.size();
      if (slots <= 0) return;
      var due =
          db.queryForList(
              "SELECT event_id FROM tasks WHERE (state='READY' AND next_at<=CURRENT_TIMESTAMP) OR"
                  + " (state='RUNNING' AND lease_until<CURRENT_TIMESTAMP) ORDER BY next_at LIMIT ?",
              String.class,
              slots);
      for (String id : due) {
        String token = UUID.randomUUID().toString();
        int changed =
            db.update(
                "UPDATE tasks SET"
                    + " state='RUNNING',lease_token=?,lease_until=CURRENT_TIMESTAMP+INTERVAL '60"
                    + " seconds',attempts=attempts+1,updated_at=CURRENT_TIMESTAMP WHERE event_id=?"
                    + " AND ((state='READY' AND next_at<=CURRENT_TIMESTAMP) OR (state='RUNNING' AND"
                    + " lease_until<CURRENT_TIMESTAMP))",
                token,
                id);
        if (changed == 1) {
          active.put(id, token);
          executor.submit(
              () -> {
                try {
                  process(id, token);
                } catch (Exception e) {
                  failed(id, token, e);
                } finally {
                  active.remove(id, token);
                }
              });
        }
      }
    } catch (Exception e) {
      /* Database outage: leases and unacknowledged tasks remain durable. */
    }
  }

  @Scheduled(fixedDelay = 15000)
  public void renew() {
    for (var e : active.entrySet())
      try {
        db.update(
            "UPDATE tasks SET lease_until=CURRENT_TIMESTAMP+INTERVAL '60 seconds' WHERE event_id=?"
                + " AND lease_token=? AND state='RUNNING'",
            e.getKey(),
            e.getValue());
      } catch (Exception ignored) {
      }
  }

  void process(String id, String token) throws Exception {
    var row = db.queryForMap("SELECT * FROM events WHERE id=?", id);
    String org = (String) row.get("org_id");
    String canonical = (String) row.get("canonical_json");
    if (!Json.sha(canonical.getBytes(StandardCharsets.UTF_8)).equals(row.get("event_sha256")))
      throw new ApiError(409, "事件原文摘要不一致");
    EvidenceService.assertBindings(
        canonical,
        db.queryForList(
            "SELECT v.* FROM evidence v JOIN event_evidence x ON x.evidence_id=v.id WHERE"
                + " x.event_id=?",
            id));
    var archived = new ArrayList<Map<String, Object>>();
    for (String eid :
        db.queryForList(
            "SELECT evidence_id FROM event_evidence WHERE event_id=? ORDER BY evidence_id",
            String.class,
            id)) {
      var ev = evidence.archive(eid, org);
      archived.add(
          Map.of(
              "id",
              eid,
              "filename",
              ev.get("filename"),
              "mime",
              ev.get("mime"),
              "sizeBytes",
              ev.get("size_bytes"),
              "sha256",
              ev.get("sha256"),
              "cid",
              ev.get("cid")));
    }
    String manifest =
        Json.write(
            Map.of(
                "schemaVersion",
                1,
                "event",
                Json.map(canonical),
                "eventSha256",
                row.get("event_sha256"),
                "evidence",
                archived));
    String manifestSha = Json.sha(manifest.getBytes(StandardCharsets.UTF_8));
    if (row.get("manifest_cid") == null) {
      String cid = ipfs.add(manifest.getBytes(StandardCharsets.UTF_8));
      if (db.update(
              "UPDATE events SET"
                  + " manifest_json=?,manifest_cid=?,manifest_sha256=?,file_state='STORED' WHERE"
                  + " id=? AND EXISTS(SELECT 1 FROM tasks WHERE event_id=? AND lease_token=?)",
              manifest,
              cid,
              manifestSha,
              id,
              id,
              token)
          == 0) return;
    } else if (!Json.sha(ipfs.cat((String) row.get("manifest_cid")))
            .equals(row.get("manifest_sha256"))
        || !manifestSha.equals(row.get("manifest_sha256"))) throw new ApiError(409, "证据清单摘要不一致");
    row = events.get(id, org);
    if (row.get("supersedes_id") != null
        && fabric.find(org, (String) row.get("supersedes_id")) == null)
      throw new IllegalStateException("等待原版本完成上链");
    var expected = AttestationRecord.record(row);
    // Reconcile every attempt, including commit timeouts and restarts, before submitting again.
    Map<String, Object> result = fabric.find(org, id);
    if (result == null)
      result =
          fabric.submit(
              expected,
              tid -> {
                int n =
                    db.update(
                        "UPDATE events SET tx_id=?,chain_state='CONFIRMING' WHERE id=? AND"
                            + " EXISTS(SELECT 1 FROM tasks WHERE event_id=? AND lease_token=?)",
                        tid,
                        id,
                        id,
                        token);
                if (n != 1) throw new IllegalStateException("任务租约已变更");
              });
    AttestationRecord.assertMatches(expected, result);
    final var confirmed = result;
    tx.executeWithoutResult(
        s -> {
          int n =
              db.update(
                  "UPDATE tasks SET"
                      + " state='DONE',lease_token=NULL,lease_until=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP"
                      + " WHERE event_id=? AND lease_token=?",
                  id,
                  token);
          if (n == 1)
            db.update(
                "UPDATE events SET"
                    + " chain_state='COMMITTED',tx_id=?,block_number=COALESCE(?,block_number),last_error=NULL"
                    + " WHERE id=?",
                confirmed.get("txId"),
                confirmed.get("blockNumber"),
                id);
        });
  }

  void failed(String id, String token, Exception error) {
    try {
      String message =
          error instanceof ApiError
              ? error.getMessage()
              : error.getClass().getSimpleName()
                  + ": "
                  + Objects.toString(error.getMessage(), "依赖暂不可用");
      message = message.substring(0, Math.min(1800, message.length()));
      final String reason = message;
      tx.executeWithoutResult(
          s -> {
            Integer attempt =
                db.queryForObject("SELECT attempts FROM tasks WHERE event_id=?", Integer.class, id);
            long delay = Math.min(300, 2L << (Math.min(attempt, 8)));
            boolean manual = error instanceof ApiError || attempt >= 10;
            int n =
                db.update(
                    "UPDATE tasks SET state=?,next_at=CURRENT_TIMESTAMP+?*INTERVAL '1"
                        + " second',lease_token=NULL,lease_until=NULL,last_error=?,updated_at=CURRENT_TIMESTAMP"
                        + " WHERE event_id=? AND lease_token=?",
                    manual ? "FAILED" : "READY",
                    delay,
                    reason,
                    id,
                    token);
            if (n == 1)
              db.update(
                  "UPDATE events SET chain_state=CASE WHEN chain_state='COMMITTED' THEN chain_state"
                      + " ELSE 'FAILED' END,last_error=? WHERE id=?",
                  reason,
                  id);
          });
    } catch (Exception ignored) {
      /* An expired lease will make this task eligible again. */
    }
  }

  @PreDestroy
  void close() {
    executor.shutdown();
    try {
      executor.awaitTermination(25, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
