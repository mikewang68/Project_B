package com.bproject.trust.ingress;

import com.bproject.trust.audit.AuditService;
import com.bproject.trust.shared.json.Json;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GenericEventService {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final AuditService audit;

  public GenericEventService(JdbcTemplate db, TransactionTemplate tx, AuditService audit) {
    this.db = db;
    this.tx = tx;
    this.audit = audit;
  }

  public record Result(int status, Map<String, Object> receipt) {}

  private static final String SCOPE =
      "e.record_kind='GENERIC_V1' AND e.org_id=? AND e.source_system=? AND e.submitted_by=?";

  public Result submit(EvidenceIngressSettings.Source source, GenericEventInput input) {
    source.require("submit");
    source.type(input.eventType());
    String request = input.request(source.sourceSystem());
    try {
      return tx.execute(
          s -> {
            EventSourceRoutes.reserve(
                db, source.orgId(), source.sourceSystem(), "GENERIC_V1", source.id());
            var existing = ownedByNumber(source, input.sourceEventId());
            if (!existing.isEmpty()) return replay(source, existing.get(0), request);
            // The unique domain includes legacy/other owners; never return their row on
            // collision.
            if (db.queryForObject(
                    "SELECT COUNT(*) FROM events WHERE org_id=? AND"
                        + " source_system=? AND source_event_id=?",
                    Long.class,
                    source.orgId(),
                    source.sourceSystem(),
                    input.sourceEventId())
                > 0) throw IngressError.missing();
            String id = UUID.randomUUID().toString(), root = id;
            int version = 1;
            if (input.supersedesId() != null) {
              var previous = owned(source, input.supersedesId());
              db.queryForList(
                  "SELECT id FROM events WHERE id=? FOR UPDATE", previous.get("root_id"));
              if (!input.eventType().equals(previous.get("event_type"))
                  || !input.businessObjectId().equals(previous.get("object_id")))
                throw new IngressError(400, "INVALID_REQUEST", "更正必须保持事件类型及业务对象");
              if (db.queryForObject(
                      "SELECT COUNT(*) FROM events WHERE supersedes_id=?",
                      Long.class,
                      input.supersedesId())
                  > 0) throw new IngressError(409, "CORRECTION_CONFLICT", "请基于最新版本追加更正");
              root = (String) previous.get("root_id");
              version = ((Number) previous.get("version")).intValue() + 1;
              if (version > 100000) throw new IngressError(409, "CORRECTION_CONFLICT", "版本数量达到上限");
            }
            byte[] original = input.original().getBytes(StandardCharsets.UTF_8);
            String originalSha = Json.sha(original);
            String json = GenericEventRules.canonical(source, input, id, root, version);
            db.update(
                "INSERT INTO"
                    + " events(id,org_id,source_system,source_event_id,event_type,batch_id,object_id,occurred_at,submitted_by,request_json,canonical_json,event_sha256,root_id,version,supersedes_id,record_kind,signing_required)"
                    + " VALUES(?,?,?,?,?,NULL,?,?,?,?,?,?,?,?,?,'GENERIC_V1',TRUE)",
                id,
                source.orgId(),
                source.sourceSystem(),
                input.sourceEventId(),
                input.eventType(),
                input.businessObjectId(),
                Timestamp.from(input.occurredAt()),
                source.actor(),
                request,
                json,
                Json.sha(json.getBytes(StandardCharsets.UTF_8)),
                root,
                version,
                input.supersedesId());
            db.update(
                "INSERT INTO"
                    + " generic_event_data(event_id,original_utf8,original_sha256,original_size_bytes)"
                    + " VALUES(?,?,?,?)",
                id,
                input.original(),
                originalSha,
                original.length);
            db.update("INSERT INTO tasks(event_id) VALUES(?)", id);
            audit.record(
                source.orgId(),
                source.actor(),
                input.supersedesId() == null ? "GENERIC_EVENT_SUBMIT" : "GENERIC_EVENT_CORRECT",
                id,
                input.supersedesId());
            var receipt = receipt(source, id);
            receipt.put("replayed", false);
            return new Result(202, receipt);
          });
    } catch (DuplicateKeyException e) {
      var existing = ownedByNumber(source, input.sourceEventId());
      if (!existing.isEmpty()) return replay(source, existing.get(0), request);
      throw new IngressError(409, "CORRECTION_CONFLICT", "记录版本已发生变化");
    }
  }

  private Result replay(
      EvidenceIngressSettings.Source source, Map<String, Object> row, String request) {
    if (!request.equals(row.get("request_json")))
      throw new IngressError(409, "IDEMPOTENCY_CONFLICT", "同一来源事件号已经登记了不同内容");
    var receipt = receipt(source, (String) row.get("id"));
    receipt.put("replayed", true);
    return new Result(200, receipt);
  }

  private List<Map<String, Object>> ownedByNumber(EvidenceIngressSettings.Source s, String number) {
    return db.queryForList(
        "SELECT e.* FROM events e WHERE " + SCOPE + " AND e.source_event_id=?",
        s.orgId(),
        s.sourceSystem(),
        s.actor(),
        number);
  }

  private Map<String, Object> owned(EvidenceIngressSettings.Source s, String id) {
    return db
        .queryForList(
            "SELECT e.* FROM events e WHERE " + SCOPE + " AND e.id=?",
            s.orgId(),
            s.sourceSystem(),
            s.actor(),
            id)
        .stream()
        .findFirst()
        .orElseThrow(IngressError::missing);
  }

  public Map<String, Object> byNumber(EvidenceIngressSettings.Source s, String number) {
    s.require("read");
    var rows = ownedByNumber(s, number);
    if (rows.isEmpty()) throw IngressError.missing();
    return receipt(s, (String) rows.get(0).get("id"));
  }

  public Map<String, Object> receipt(EvidenceIngressSettings.Source source, String id) {
    source.require("read");
    // A single statement gives the receipt one MVCC snapshot while the worker is changing
    // state.
    var e =
        db
            .queryForList(
                "SELECT e.*,t.state AS"
                    + " task_state,t.attempts,t.next_at,g.original_sha256,g.original_cid,g.last_error_code,EXISTS(SELECT"
                    + " 1 FROM signing_attempts s WHERE s.event_id=e.id AND s.state IN"
                    + " ('PREPARED','UNKNOWN')) AS uncertain FROM events e JOIN tasks t ON"
                    + " t.event_id=e.id JOIN generic_event_data g ON g.event_id=e.id WHERE "
                    + SCOPE
                    + " AND e.id=?",
                source.orgId(),
                source.sourceSystem(),
                source.actor(),
                id)
            .stream()
            .findFirst()
            .orElseThrow(IngressError::missing);
    return GenericEventRules.receipt(e);
  }

  public static String status(String task, String chain, int attempts, boolean uncertain) {
    return GenericEventRules.status(task, chain, attempts, uncertain);
  }

  public Result retry(EvidenceIngressSettings.Source source, String id) {
    source.require("retry");
    return tx.execute(
        s -> {
          // Check scope before locking; lock task then event, as worker completion does.
          owned(source, id);
          var task = db.queryForMap("SELECT * FROM tasks WHERE event_id=? FOR UPDATE", id);
          db.queryForList("SELECT id FROM events WHERE id=? FOR UPDATE", id);
          var event = owned(source, id);
          source.type((String) event.get("event_type"));
          String state = (String) task.get("state");
          boolean queued = GenericEventRules.retryQueued(state, (String) event.get("chain_state"));
          if (queued) {
            db.update(
                "UPDATE tasks SET"
                    + " state='READY',attempts=0,next_at=CURRENT_TIMESTAMP,lease_token=NULL,lease_until=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP"
                    + " WHERE event_id=?",
                id);
            db.update(
                "UPDATE generic_event_data SET last_error_code=NULL WHERE" + " event_id=?", id);
            audit.record(
                source.orgId(),
                source.actor(),
                "GENERIC_EVENT_RETRY",
                id,
                Json.write(
                    Map.of("previousState", state, "previousAttempts", task.get("attempts"))));
          }
          var receipt = receipt(source, id);
          receipt.put("queued", queued);
          return new Result(queued ? 202 : 200, receipt);
        });
  }
}
