package com.bproject.trust.events;

import com.bproject.trust.audit.AuditService;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EventService {
  final JdbcTemplate db;
  final TransactionTemplate tx;
  private final AuditService audit;
  private final EventRelations relations;

  public EventService(
      JdbcTemplate db, TransactionTemplate tx, AuditService audit, EventRelations relations) {
    this.db = db;
    this.tx = tx;
    this.audit = audit;
    this.relations = relations;
  }

  public Map<String, Object> submit(EventInput raw, String org, String actor, String previous) {
    EventInput input = raw.normalized();
    String request =
        Json.write(Map.of("event", input, "supersedesId", previous == null ? "" : previous));
    if (request.getBytes(StandardCharsets.UTF_8).length > 262144) throw new ApiError(413, "事件内容过大");
    try {
      return tx.execute(
          status -> {
            var existing =
                db.queryForList(
                    "SELECT * FROM events WHERE org_id=? AND source_system=? AND source_event_id=?",
                    org,
                    input.sourceSystem(),
                    input.sourceEventId());
            if (!existing.isEmpty()) return replay(existing.get(0), request);
            String id = UUID.randomUUID().toString(), root = id;
            int version = 1;
            if (previous != null) {
              var old = get(previous, org);
              db.queryForList("SELECT id FROM events WHERE id=? FOR UPDATE", old.get("root_id"));
              if (!old.get("source_system").equals(input.sourceSystem()))
                throw new ApiError(409, "更正必须保持来源系统一致");
              if (db.queryForObject(
                      "SELECT COUNT(*) FROM events WHERE supersedes_id=?", Long.class, previous)
                  > 0) throw new ApiError(409, "原记录已有更正，请基于最新版本追加");
              root = (String) old.get("root_id");
              version = ((Number) old.get("version")).intValue() + 1;
            }
            var evidence = new ArrayList<Map<String, Object>>();
            for (String eid : input.evidenceIds()) {
              var rows =
                  db.queryForList(
                      "SELECT id,filename,mime,size_bytes,sha256 FROM evidence WHERE id=? AND"
                          + " org_id=?",
                      eid,
                      org);
              if (rows.isEmpty()) throw new ApiError(400, "证据不存在或不在当前授权范围");
              evidence.add(rows.get(0));
            }
            Map<String, Object> canonical = new TreeMap<>();
            canonical.put("schemaVersion", 1);
            canonical.put("id", id);
            canonical.put("orgId", org);
            canonical.put("submittedBy", actor);
            canonical.put("rootId", root);
            canonical.put("version", version);
            canonical.put("supersedesId", previous);
            canonical.put("event", input);
            canonical.put("evidence", evidence);
            String json = Json.write(canonical),
                sha = Json.sha(json.getBytes(StandardCharsets.UTF_8));
            db.update(
                "INSERT INTO"
                    + " events(id,org_id,source_system,source_event_id,event_type,batch_id,object_id,occurred_at,submitted_by,request_json,canonical_json,event_sha256,root_id,version,supersedes_id)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,
                org,
                input.sourceSystem(),
                input.sourceEventId(),
                input.eventType(),
                input.batchId(),
                input.businessObjectId(),
                Timestamp.from(input.occurredAt()),
                actor,
                request,
                json,
                sha,
                root,
                version,
                previous);
            for (String eid : input.evidenceIds())
              db.update("INSERT INTO event_evidence VALUES(?,?)", id, eid);
            link(id, "BATCH", input.batchId());
            for (String b : input.relatedBatchIds())
              if (!b.equals(input.batchId())) link(id, "BATCH", b);
            for (String b : input.bundleIds()) link(id, "BUNDLE", b);
            if (input.handoverId() != null && !input.handoverId().isBlank())
              link(id, "HANDOVER", input.handoverId());
            for (String ref : input.relatedEventRefs()) link(id, "EVENT", ref);
            db.update("INSERT INTO tasks(event_id) VALUES(?)", id);
            audit.record(
                org, actor, previous == null ? "EVENT_SUBMIT" : "EVENT_CORRECT", id, previous);
            return get(id, org);
          });
    } catch (DuplicateKeyException e) {
      var existing =
          db.queryForList(
              "SELECT * FROM events WHERE org_id=? AND source_system=? AND source_event_id=?",
              org,
              input.sourceSystem(),
              input.sourceEventId());
      if (!existing.isEmpty()) return replay(existing.get(0), request);
      throw new ApiError(409, "记录版本已发生变化，请读取最新版本后重试");
    }
  }

  private Map<String, Object> replay(Map<String, Object> row, String request) {
    if (!row.get("request_json").equals(request)) throw new ApiError(409, "同一来源事件号已经登记了不同内容");
    return row;
  }

  private void link(String id, String kind, String target) {
    db.update("INSERT INTO event_links VALUES(?,?,?)", id, kind, target);
  }

  public Map<String, Object> get(String id, String org) {
    return db.queryForList("SELECT * FROM events WHERE id=? AND org_id=?", id, org).stream()
        .findFirst()
        .orElseThrow(() -> new ApiError(404, "记录不存在或无权访问"));
  }

  public Map<String, Object> detail(String id, String org) {
    Map<String, Object> row = get(id, org);
    row.put(
        "evidence",
        db.queryForList(
            "SELECT v.* FROM evidence v JOIN event_evidence x ON x.evidence_id=v.id WHERE"
                + " x.event_id=? AND v.org_id=? ORDER BY v.id",
            id,
            org));
    row.put(
        "versions",
        db.queryForList(
            "SELECT id,version,supersedes_id,chain_state FROM events WHERE root_id=? AND org_id=?"
                + " ORDER BY version",
            row.get("root_id"),
            org));
    row.put("links", db.queryForList("SELECT kind,target FROM event_links WHERE event_id=?", id));
    row.put("missingReferences", relations.missing(row, org));
    return row;
  }

  public Map<String, Object> list(String org, String query, int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw new ApiError(400, "分页参数超出范围");
    String q = "%" + (query == null ? "" : query) + "%";
    String where =
        " FROM events WHERE org_id=? AND (batch_id LIKE ? OR source_event_id LIKE ? OR object_id"
            + " LIKE ?)";
    return Map.of(
        "total",
        db.queryForObject("SELECT COUNT(*)" + where, Long.class, org, q, q, q),
        "items",
        db.queryForList(
            "SELECT"
                + " id,source_system,source_event_id,event_type,batch_id,object_id,occurred_at,received_at,version,file_state,chain_state,last_error"
                + where
                + " ORDER BY received_at DESC,id LIMIT ? OFFSET ?",
            org,
            q,
            q,
            q,
            size,
            page * size));
  }
}
