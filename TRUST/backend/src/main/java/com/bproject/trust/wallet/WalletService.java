package com.bproject.trust.wallet;

import com.bproject.trust.audit.AuditService;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WalletService {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final WalletKeyStore keys;
  private final AuditService audit;

  public WalletService(
      JdbcTemplate db, TransactionTemplate tx, WalletKeyStore keys, AuditService audit) {
    this.db = db;
    this.tx = tx;
    this.keys = keys;
    this.audit = audit;
  }

  public Map<String, Object> list(String org) {
    return Map.of(
        "wallets",
        db.queryForList(
            "SELECT id,label,msp_id,key_ref,fingerprint,subject,not_after,state,created_at FROM"
                + " wallets WHERE org_id=? ORDER BY created_at DESC",
            org),
        "bindings",
        db.queryForList("SELECT * FROM wallet_bindings WHERE org_id=? ORDER BY source_system", org),
        "requests",
        db.queryForList(
            "SELECT * FROM wallet_requests WHERE org_id=? ORDER BY created_at DESC LIMIT 100", org),
        "signatures",
        db.queryForList(
            "SELECT s.*,e.source_system,e.source_event_id,e.submitted_by,e.chain_state FROM"
                + " signing_attempts s JOIN events e ON e.id=s.event_id WHERE e.org_id=? ORDER BY"
                + " s.created_at DESC LIMIT 100",
            org));
  }

  public record Change(
      String label,
      String mspId,
      String keyRef,
      String walletId,
      String sourceSystem,
      Integer expectedRevision,
      String certificateFingerprint) {
    public Change(
        String label,
        String mspId,
        String keyRef,
        String walletId,
        String sourceSystem,
        Integer expectedRevision) {
      this(label, mspId, keyRef, walletId, sourceSystem, expectedRevision, null);
    }
  }

  public Map<String, Object> propose(
      String org, String actor, String action, Change change, String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 500)
      throw new ApiError(400, "请填写变更原因（500 字以内）");
    validate(org, action, change);
    Change snapshot =
        "REGISTER".equals(action)
            ? new Change(
                change.label(),
                change.mspId(),
                change.keyRef(),
                null,
                null,
                null,
                material(change.keyRef()).fingerprint())
            : change;
    String id = UUID.randomUUID().toString();
    tx.executeWithoutResult(
        s -> {
          db.update(
              "INSERT INTO wallet_requests(id,org_id,action,payload,reason,proposed_by)"
                  + " VALUES(?,?,?,?,?,?)",
              id,
              org,
              action,
              Json.write(snapshot),
              reason,
              actor);
          audit.record(org, actor, "WALLET_PROPOSE", id, action + ": " + reason);
        });
    return Map.of("id", id, "state", "PENDING");
  }

  private void validate(String org, String action, Change c) {
    if (c == null || action == null) throw new ApiError(400, "缺少变更内容");
    switch (action) {
      case "REGISTER" -> {
        if (c.label() == null
            || c.label().isBlank()
            || c.label().length() > 120
            || c.mspId() == null
            || !c.mspId().matches("[A-Za-z0-9._-]{1,80}")) throw new ApiError(400, "钱包名称或 MSP 无效");
        var m = material(c.keyRef());
        if (c.certificateFingerprint() != null
            && !c.certificateFingerprint().equals(m.fingerprint()))
          throw new ApiError(409, "申请后证书已变化，请重新申请并复核");
      }
      case "BIND" -> {
        active(c.walletId(), org);
        if (c.sourceSystem() == null
            || !c.sourceSystem().matches("[A-Za-z0-9._-]{1,80}")
            || c.expectedRevision() == null
            || c.expectedRevision() < 0) throw new ApiError(400, "来源系统或预期版本无效");
      }
      case "DISABLE" -> wallet(c.walletId(), org);
      default -> throw new ApiError(400, "不支持的变更类型");
    }
  }

  private WalletKeyStore.Material material(String ref) {
    try {
      return keys.load(ref);
    } catch (Exception e) {
      throw new ApiError(400, "证书/密钥引用不可用、已过期或不匹配");
    }
  }

  public Map<String, Object> review(String org, String actor, String id, boolean approve) {
    return tx.execute(
        s -> {
          var rows =
              db.queryForList(
                  "SELECT * FROM wallet_requests WHERE id=? AND org_id=? FOR UPDATE", id, org);
          if (rows.isEmpty()) throw new ApiError(404, "变更申请不存在");
          var request = rows.get(0);
          if (actor.equals(request.get("proposed_by"))) throw new ApiError(403, "申请人与复核人必须不同");
          if (!"PENDING".equals(request.get("state"))) throw new ApiError(409, "申请已处理");
          Change c;
          try {
            c = Json.MAPPER.readValue((String) request.get("payload"), Change.class);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
          String action = (String) request.get("action");
          if (approve) {
            validate(org, action, c);
            switch (action) {
              case "REGISTER" -> {
                var m = material(c.keyRef());
                db.update(
                    "INSERT INTO"
                        + " wallets(id,org_id,label,msp_id,key_ref,certificate_pem,fingerprint,subject,not_after)"
                        + " VALUES(?,?,?,?,?,?,?,?,?)",
                    id,
                    org,
                    c.label(),
                    c.mspId(),
                    c.keyRef(),
                    m.pem(),
                    m.fingerprint(),
                    m.certificate().getSubjectX500Principal().getName(),
                    new Timestamp(m.certificate().getNotAfter().getTime()));
              }
              case "BIND" -> {
                // Lock the wallet so an approval cannot race a disable of this identity.
                db.queryForMap("SELECT id FROM wallets WHERE id=? FOR UPDATE", c.walletId());
                active(c.walletId(), org);
                if (c.expectedRevision() == 0) {
                  if (!db.queryForList(
                          "SELECT 1 FROM wallet_bindings WHERE org_id=? AND source_system=?",
                          org,
                          c.sourceSystem())
                      .isEmpty()) throw new ApiError(409, "绑定已变化，请重新申请");
                  try {
                    db.update(
                        "INSERT INTO wallet_bindings(org_id,source_system,wallet_id,change_request_id) VALUES(?,?,?,?)",
                        org, c.sourceSystem(), c.walletId(), id);
                  } catch (DuplicateKeyException conflict) {
                    throw new ApiError(409, "绑定已变化，请重新申请");
                  }
                } else if (db.update(
                        "UPDATE wallet_bindings SET wallet_id=?,change_request_id=?,revision=revision+1 WHERE org_id=?"
                            + " AND source_system=? AND revision=?",
                        c.walletId(),
                        id,
                        org,
                        c.sourceSystem(),
                        c.expectedRevision())
                    != 1) throw new ApiError(409, "绑定已变化，请重新申请");
              }
              case "DISABLE" ->
                  db.update(
                      "UPDATE wallets SET state='DISABLED' WHERE id=? AND org_id=?",
                      c.walletId(),
                      org);
            }
          }
          db.update(
              "UPDATE wallet_requests SET state=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP WHERE"
                  + " id=?",
              approve ? "APPROVED" : "REJECTED",
              actor,
              id);
          audit.record(
              org,
              actor,
              "WALLET_REVIEW",
              id,
              Json.write(
                  Map.of(
                      "action",
                      action,
                      "approved",
                      approve,
                      "proposedBy",
                      request.get("proposed_by"))));
          return Map.of("id", id, "state", approve ? "APPROVED" : "REJECTED");
        });
  }

  public Map<String, Object> wallet(String id, String org) {
    return db.queryForList("SELECT * FROM wallets WHERE id=? AND org_id=?", id, org).stream()
        .findFirst()
        .orElseThrow(() -> new ApiError(404, "钱包不存在或无权访问"));
  }

  public Map<String, Object> policy(String org, String requestId, int revision) {
    if (revision < 1) throw new ApiError(400, "链上策略版本必须大于零");
    var r =
        db
            .queryForList(
                "SELECT * FROM wallet_requests WHERE id=? AND org_id=? AND state='APPROVED' AND"
                    + " action='BIND'",
                requestId,
                org)
            .stream()
            .findFirst()
            .orElseThrow(() -> new ApiError(404, "已批准的绑定申请不存在"));
    Change c;
    try {
      c = Json.MAPPER.readValue((String) r.get("payload"), Change.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    var binding =
        db.queryForList(
            "SELECT wallet_id,change_request_id FROM wallet_bindings WHERE org_id=? AND source_system=?",
            org,
            c.sourceSystem());
    if (binding.isEmpty() || !requestId.equals(binding.get(0).get("change_request_id")))
      throw new ApiError(409, "此绑定申请已被后续变更替代");
    var w = active(c.walletId(), org);
    return Map.of(
        "orgId",
        org,
        "sourceSystem",
        c.sourceSystem(),
        "mspId",
        w.get("msp_id"),
        "fingerprint",
        w.get("fingerprint"),
        "enabled",
        true,
        "revision",
        revision,
        "eventTypes",
        java.util.List.of("WAREHOUSE_IN", "DISPATCH"),
        "changeId",
        requestId);
  }

  public Map<String, Object> active(String id, String org) {
    var row = wallet(id, org);
    if (!"ACTIVE".equals(row.get("state"))) throw new ApiError(409, "签名钱包已停用");
    var m = material((String) row.get("key_ref"));
    if (!m.fingerprint().equals(row.get("fingerprint"))) throw new ApiError(409, "证书版本已被替换，请追加新版本");
    return row;
  }

  public void prepare(String eventId) {
    tx.executeWithoutResult(
        s -> {
          var event = db.queryForMap("SELECT * FROM events WHERE id=? FOR UPDATE", eventId);
          if (!Boolean.TRUE.equals(event.get("signing_required"))) return;
          String org = (String) event.get("org_id"), id = (String) event.get("wallet_id");
          var existing = db.queryForList("SELECT context_json,context_sha256 FROM event_signing_context WHERE event_id=?", eventId);
          if (existing.isEmpty()) {
            if (id != null) throw new ApiError(409, "历史签名缺少批准快照，禁止根据当前绑定补写");
            var bindings =
                db.queryForList(
                    "SELECT wallet_id,revision,change_request_id FROM wallet_bindings WHERE org_id=? AND source_system=?",
                    org,
                    event.get("source_system"));
            if (bindings.isEmpty()) throw new ApiError(409, "该组织与来源系统尚未绑定签名钱包");
            var binding = bindings.get(0);
            id = (String) binding.get("wallet_id");
            db.queryForMap("SELECT id FROM wallets WHERE id=? FOR UPDATE", id);
            var wallet = active(id, org);
            var registration = approval(id, org, "REGISTER");
            var bindingApproval = approval((String) binding.get("change_request_id"), org, "BIND");
            Map<String, Object> context = new TreeMap<>();
            context.put("schemaVersion", 1);
            context.put("eventId", eventId);
            context.put("orgId", org);
            context.put("sourceSystem", event.get("source_system"));
            context.put("sourceEventId", event.get("source_event_id"));
            context.put("submittedBy", event.get("submitted_by"));
            context.put("eventSha256", event.get("event_sha256"));
            // Keep the original business event, including operator and source-service assertion.
            context.put("businessEvent", Json.map((String) event.get("canonical_json")).get("event"));
            context.put("walletId", id);
            context.put("mspId", wallet.get("msp_id"));
            context.put("certificateFingerprint", wallet.get("fingerprint"));
            context.put("bindingRevision", binding.get("revision"));
            context.put("registrationApproval", registration);
            context.put("bindingApproval", bindingApproval);
            String json = Json.write(context);
            db.update("INSERT INTO event_signing_context(event_id,context_json,context_sha256) VALUES(?,?,?)",
                eventId, json, Json.sha(json.getBytes(StandardCharsets.UTF_8)));
          } else {
            var context = existing.get(0);
            if (!Json.sha(((String) context.get("context_json")).getBytes(StandardCharsets.UTF_8))
                .equals(context.get("context_sha256"))) throw new ApiError(409, "签名批准快照摘要不一致");
            var snapshot = Json.map((String) context.get("context_json"));
            if (!snapshot.get("walletId").equals(id)
                || !snapshot.get("certificateFingerprint").equals(event.get("signer_fingerprint"))
                || !snapshot.get("eventSha256").equals(event.get("event_sha256")))
              throw new ApiError(409, "签名身份或事件与首次批准快照不一致");
          }
          var wallet = active(id, org);
          db.update(
              "UPDATE events SET wallet_id=?,signer_fingerprint=? WHERE id=?",
              id,
              wallet.get("fingerprint"),
              eventId);
        });
  }

  private Map<String, Object> approval(String id, String org, String action) {
    var rows = db.queryForList("SELECT id,proposed_by,reviewed_by,reason,reviewed_at FROM wallet_requests"
        + " WHERE id=? AND org_id=? AND action=? AND state='APPROVED'", id, org, action);
    if (rows.isEmpty()) throw new ApiError(409, "签名绑定缺少可追溯的批准记录，请重新申请绑定");
    var row = rows.get(0);
    return Map.of("requestId", row.get("id"), "proposedBy", row.get("proposed_by"),
        "reviewedBy", row.get("reviewed_by"), "reason", row.get("reason"),
        "reviewedAt", row.get("reviewed_at").toString());
  }

  public void prepared(String eventId, String txId) {
    tx.executeWithoutResult(
        s -> {
          var event = db.queryForMap("SELECT * FROM events WHERE id=? FOR UPDATE", eventId);
          if (event.get("wallet_id") == null) return;
          active((String) event.get("wallet_id"), (String) event.get("org_id"));
          var context = db.queryForMap("SELECT context_json,context_sha256 FROM event_signing_context WHERE event_id=?", eventId);
          db.update(
              "INSERT INTO signing_attempts(tx_id,event_id,wallet_id,fingerprint,context_json,context_sha256) VALUES(?,?,?,?,?,?)",
              txId,
              eventId,
              event.get("wallet_id"),
              event.get("signer_fingerprint"), context.get("context_json"), context.get("context_sha256"));
          audit.record(
              (String) event.get("org_id"), "signing-service", "SIGN_PREPARED", eventId, txId);
        });
  }

  /** Called inside the worker's completion transaction, including recovery after commit timeout. */
  public void committed(String eventId, Map<String, Object> receipt) {
    db.update("UPDATE signing_attempts SET state=CASE WHEN tx_id=? THEN 'COMMITTED' WHEN state='INVALID' THEN state ELSE 'SUPERSEDED' END,"
        + " ledger_identity=CASE WHEN tx_id=? THEN ? ELSE ledger_identity END,"
        + " block_number=CASE WHEN tx_id=? THEN ? ELSE block_number END,"
        + " resolved_at=CURRENT_TIMESTAMP WHERE event_id=?",
        receipt.get("txId"), receipt.get("txId"), receipt.get("ledgerIdentity"),
        receipt.get("txId"), receipt.get("blockNumber"), eventId);
  }

  public void rejected(String eventId, String txId, long blockNumber, String reason) {
    db.update("UPDATE signing_attempts SET state='INVALID',block_number=?,last_error=?,"
        + " resolved_at=CURRENT_TIMESTAMP WHERE event_id=? AND tx_id=? AND state IN ('PREPARED','UNKNOWN')",
        blockNumber, reason, eventId, txId);
  }

  /** A transport failure is not proof that Fabric rejected a transaction. */
  public void uncertain(String eventId, String reason) {
    db.update("UPDATE signing_attempts SET state='UNKNOWN',last_error=? WHERE event_id=? AND state='PREPARED'",
        reason, eventId);
  }
}
