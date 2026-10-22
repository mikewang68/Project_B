package com.bproject.trust.archiving;

import com.bproject.trust.events.EventService;
import com.bproject.trust.evidence.EvidenceService;
import com.bproject.trust.ports.EvidenceStorage;
import com.bproject.trust.ports.LedgerGateway;
import com.bproject.trust.shared.BoundedWork;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "trust.archiving.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ArchiveWorker {
  final JdbcTemplate db;
  final EventService events;
  final EvidenceService evidence;
  final EvidenceStorage ipfs;
  final LedgerGateway fabric;
  final TransactionTemplate tx;
  final com.bproject.trust.wallet.WalletService wallets;
  final com.bproject.trust.ingress.EvidenceIngressSettings ingress;
  final com.bproject.trust.ingress.GenericEvidenceArchive generic;
  final BoundedWork executor = new BoundedWork("trust-archive", 4);
  final ConcurrentHashMap<String, String> active = new ConcurrentHashMap<>();

  @org.springframework.beans.factory.annotation.Autowired
  private com.bproject.trust.logevidence.LogChainGate logChainGate;

  public ArchiveWorker(
      JdbcTemplate db,
      EventService events,
      EvidenceService evidence,
      EvidenceStorage ipfs,
      LedgerGateway fabric,
      TransactionTemplate tx,
      com.bproject.trust.wallet.WalletService wallets,
      com.bproject.trust.ingress.EvidenceIngressSettings ingress,
      com.bproject.trust.ingress.GenericEvidenceArchive generic) {
    this.db = db;
    this.events = events;
    this.evidence = evidence;
    this.ipfs = ipfs;
    this.fabric = fabric;
    this.tx = tx;
    this.wallets = wallets;
    this.ingress = ingress;
    this.generic = generic;
  }

  @Scheduled(fixedDelay = 1000)
  public synchronized void poll() {
    if (executor.closing()) return;
    try {
      int slots = 4 - active.size();
      if (slots <= 0) return;
      var due = due(slots);
      for (String id : due) {
        if (active.containsKey(id)) continue;
        String token = UUID.randomUUID().toString();
        int changed =
            db.update(
                "UPDATE tasks_read SET"
                    + " state='RUNNING',lease_token=?,lease_until=CURRENT_TIMESTAMP+INTERVAL '60"
                    + " seconds',attempts=attempts+1,updated_at=CURRENT_TIMESTAMP WHERE event_id=?"
                    + " AND ((state='READY' AND next_at<=CURRENT_TIMESTAMP) OR (state='RUNNING' AND"
                    + " lease_until<CURRENT_TIMESTAMP))",
                token,
                id);
        if (changed == 1) {
          active.put(id, token);
          boolean accepted =
              executor.execute(
                  () -> {
                    try {
                      process(id, token);
                    } catch (Exception e) {
                      failed(id, token, e);
                    } finally {
                      active.remove(id, token);
                    }
                  });
          if (!accepted) {
            active.remove(id, token);
            defer(id, token);
          }
        }
      }
    } catch (Exception e) {
      com.bproject.trust.shared.WorkerSignals.failure("ArchiveWorker", "poll", e);
    }
  }

  private java.util.List<String> due(int slots) {
    var arguments = new ArrayList<Object>();
    StringBuilder scope = new StringBuilder("e.record_kind='LEGACY_V1'");
    for (var source : ingress.processingSources()) {
      scope.append(
          " OR (e.record_kind='GENERIC_V1' AND e.org_id=? AND e.source_system=? AND"
              + " e.submitted_by=? AND e.event_type IN (");
      arguments.add(source.orgId());
      arguments.add(source.sourceSystem());
      arguments.add(source.actor());
      scope
          .append(
              String.join(
                  ",", java.util.Collections.nCopies(source.allowedEventTypes().size(), "?")))
          .append("))");
      arguments.addAll(source.allowedEventTypes());
    }
    arguments.add(slots);
    return db.queryForList(
        "SELECT t.event_id FROM tasks_read t JOIN events_read e ON e.id=t.event_id WHERE"
            + " ((t.state='READY' AND t.next_at<=CURRENT_TIMESTAMP) OR (t.state='RUNNING' AND"
            + " t.lease_until<CURRENT_TIMESTAMP)) AND ("
            + scope
            + ") ORDER BY t.next_at LIMIT ?",
        String.class,
        arguments.toArray());
  }

  @Scheduled(fixedDelay = 15000, scheduler = "leaseScheduler")
  public void renew() {
    for (var e : active.entrySet())
      try {
        db.update(
            "UPDATE tasks_read SET lease_until=CURRENT_TIMESTAMP+INTERVAL '60 seconds' WHERE"
                + " event_id=? AND lease_token=? AND state='RUNNING' AND"
                + " lease_until>clock_timestamp()",
            e.getKey(),
            e.getValue());
      } catch (Exception ignored) {
        com.bproject.trust.shared.WorkerSignals.failure("ArchiveWorker", "persistence", ignored);
      }
  }

  void process(String id, String token) throws Exception {
    var row = db.queryForMap("SELECT * FROM events_read WHERE id=?", id);
    if (!proceed(row, id, token, true)) return;
    String org = (String) row.get("org_id");
    if (logChainGate != null && !logChainGate.allows(id)) {
      // Pausing prevents new submissions, not reconciliation of an uncertain prior transaction.
      boolean possiblySubmitted =
          row.get("tx_id") != null
              || db.queryForObject(
                      "SELECT COUNT(*) FROM signing_attempts_read WHERE event_id=?", Long.class, id)
                  > 0;
      if (!possiblySubmitted) {
        defer(id, token);
        return;
      }
      Map<String, Object> confirmed;
      try {
        confirmed = fabric.find(org, id);
      } catch (Exception temporarilyUnavailable) {
        com.bproject.trust.shared.WorkerSignals.failure(
            "ArchiveWorker", "poll", temporarilyUnavailable);
        if (temporarilyUnavailable instanceof InterruptedException)
          Thread.currentThread().interrupt();
        // A paused reconciliation is not a submission attempt. Keep its original failure budget.
        defer(id, token);
        return;
      }
      if (confirmed != null && row.get("manifest_cid") != null && row.get("wallet_id") != null) {
        AttestationRecord.assertMatches(AttestationRecord.record(row), confirmed);
        complete(id, token, confirmed);
      } else {
        defer(id, token);
      }
      return;
    }
    String canonical = (String) row.get("canonical_json");
    if (!Json.sha(canonical.getBytes(StandardCharsets.UTF_8)).equals(row.get("event_sha256")))
      throw new ApiError(409, "事件原文摘要不一致");
    EvidenceService.assertBindings(
        canonical,
        db.queryForList(
            "SELECT v.* FROM evidence_read v JOIN event_evidence x ON x.evidence_id=v.id WHERE"
                + " x.event_id=?",
            id));
    var archived = new ArrayList<Map<String, Object>>();
    for (String eid :
        db.queryForList(
            "SELECT evidence_id FROM event_evidence WHERE event_id=? ORDER BY evidence_id",
            String.class,
            id)) {
      if (!proceed(row, id, token)) return;
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
        com.bproject.trust.ingress.GenericEvidenceArchive.generic(row)
            ? generic.manifest(row, token)
            : Json.write(
                Map.of(
                    "schemaVersion",
                    1,
                    "event",
                    Json.map(canonical),
                    "eventSha256",
                    row.get("event_sha256"),
                    "evidence",
                    archived));
    if (manifest == null || !proceed(row, id, token)) return;
    String manifestSha = Json.sha(manifest.getBytes(StandardCharsets.UTF_8));
    if (row.get("manifest_cid") == null) {
      String cid = ipfs.add(manifest.getBytes(StandardCharsets.UTF_8));
      if (db.update(
              "UPDATE events_read SET"
                  + " expected_lease_token=?,manifest_json=?,manifest_cid=?,manifest_sha256=?,file_state='STORED'"
                  + " WHERE id=? AND EXISTS(SELECT 1 FROM tasks_read WHERE event_id=? AND"
                  + " lease_token=? AND state='RUNNING' AND lease_until>clock_timestamp())",
              token,
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
    // Reconcile every attempt, including commit timeouts and restarts, before submitting again.
    Map<String, Object> result = fabric.find(org, id);
    if (result == null) {
      if (!proceed(row, id, token)) return;
      wallets.prepare(id);
      row = events.get(id, org);
    }
    if (com.bproject.trust.ingress.GenericEvidenceArchive.generic(row)
        && row.get("wallet_id") == null)
      throw new com.bproject.trust.ingress.IngressError(
          409, "SIGNING_NOT_READY", "通用事件必须使用已批准的真实签名钱包");
    var expected = AttestationRecord.record(row);
    if (result == null) {
      if (!proceed(row, id, token)) return;
      result =
          fabric.submit(
              expected,
              tid -> {
                tx.executeWithoutResult(
                    status -> {
                      com.bproject.trust.shared.RetentionLock.row(db, "tasks", "event_id=?", id);
                      wallets.prepared(id, tid);
                      int n =
                          db.update(
                              "UPDATE events_read SET"
                                  + " expected_lease_token=?,tx_id=?,chain_state='CONFIRMING' WHERE"
                                  + " id=? AND EXISTS(SELECT 1 FROM tasks_read WHERE event_id=? AND"
                                  + " lease_token=? AND state='RUNNING' AND"
                                  + " lease_until>clock_timestamp())",
                              token,
                              tid,
                              id,
                              id,
                              token);
                      if (n != 1) throw new IllegalStateException("任务租约已变更");
                    });
              });
    }
    AttestationRecord.assertMatches(expected, result);
    complete(id, token, result);
  }

  private void complete(String id, String token, Map<String, Object> confirmed) {
    if (executor.closing()) return;
    tx.executeWithoutResult(
        s -> {
          int n =
              db.update(
                  "UPDATE tasks_read SET"
                      + " state='DONE',lease_token=NULL,lease_until=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP"
                      + " WHERE event_id=? AND lease_token=? AND state='RUNNING' AND"
                      + " lease_until>clock_timestamp()",
                  id,
                  token);
          if (n == 1) {
            wallets.committed(id, confirmed);
            db.update(
                "UPDATE generic_event_data_read SET last_error_code=NULL WHERE event_id=?", id);
            db.update(
                "UPDATE events_read SET"
                    + " chain_state='COMMITTED',tx_id=?,block_number=COALESCE(?,block_number),ledger_identity=?,last_error=NULL"
                    + " WHERE id=?",
                confirmed.get("txId"),
                confirmed.get("blockNumber"),
                confirmed.get("ledgerIdentity"),
                id);
          }
        });
  }

  private boolean proceed(Map<String, Object> row, String id, String token) {
    return proceed(row, id, token, false);
  }

  private boolean proceed(Map<String, Object> row, String id, String token, boolean reconcile) {
    if (executor.closing()) return false;
    if (db.queryForObject(
            "SELECT COUNT(*) FROM tasks_read WHERE event_id=? AND lease_token=? AND state='RUNNING'"
                + " AND lease_until>clock_timestamp()",
            Long.class,
            id,
            token)
        == 0) return false;
    if (ingress.permits(row) && (reconcile || logChainGate == null || logChainGate.allows(id)))
      return true;
    defer(id, token);
    return false;
  }

  private void defer(String id, String token) {
    db.update(
        "UPDATE tasks_read SET state='READY',next_at=CURRENT_TIMESTAMP+INTERVAL '5 seconds',"
            + " attempts=GREATEST(attempts-1,0),lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP"
            + " WHERE event_id=? AND lease_token=? AND state='RUNNING' AND"
            + " lease_until>clock_timestamp()",
        id,
        token);
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
                db.queryForObject(
                    "SELECT attempts FROM tasks_read WHERE event_id=?", Integer.class, id);
            long delay = Math.min(300, 2L << (Math.min(attempt, 8)));
            boolean manual = error instanceof ApiError || attempt >= 10;
            int n =
                db.update(
                    "UPDATE tasks_read SET state=?,next_at=CURRENT_TIMESTAMP+?*INTERVAL '1"
                        + " second',lease_token=NULL,lease_until=NULL,last_error=?,updated_at=CURRENT_TIMESTAMP"
                        + " WHERE event_id=? AND lease_token=? AND state='RUNNING' AND"
                        + " lease_until>clock_timestamp()",
                    manual ? "FAILED" : "READY",
                    delay,
                    reason,
                    id,
                    token);
            if (n == 1) {
              String code =
                  error instanceof com.bproject.trust.ingress.IngressError e
                      ? e.code
                      : error instanceof ApiError ? "SIGNING_NOT_READY" : "DEPENDENCY_UNAVAILABLE";
              db.update(
                  "UPDATE generic_event_data_read SET last_error_code=? WHERE event_id=?",
                  code,
                  id);
              wallets.uncertain(id, reason);
              db.update(
                  "UPDATE events_read SET chain_state=CASE WHEN chain_state='COMMITTED' THEN"
                      + " chain_state ELSE 'FAILED' END,last_error=? WHERE id=?",
                  reason,
                  id);
            }
          });
    } catch (Exception ignored) {
      com.bproject.trust.shared.WorkerSignals.failure("ArchiveWorker", "persistence", ignored);
      /* An expired lease will make this task eligible again. */
    }
  }

  @PreDestroy
  void close() {
    executor.close();
  }
}
