package com.bproject.trust.provisioning;

import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FabricIdentityService {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public FabricIdentityService(JdbcTemplate db, TransactionTemplate tx) {
    this.db = db;
    this.tx = tx;
  }

  public record Command(
      String issuer,
      String tenant,
      String userId,
      String orgId,
      long revision,
      String desiredState,
      String action) {}

  public Map<String, Object> provision(IdentityProviderSettings.Client c, String key, Command r) {
    validate(c, key, r);
    String hash = Json.sha(Json.write(r).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        String id =
            tx.execute(
                s -> {
                  var prior =
                      db.queryForList(
                          "SELECT * FROM identity_request WHERE caller_id=? AND request_key=?",
                          c.id(),
                          key);
                  if (!prior.isEmpty()) {
                    if (!hash.equals(prior.get(0).get("request_hash")))
                      throw new ApiError(409, "同一幂等键的请求内容不同");
                    return (String) prior.get(0).get("identity_id");
                  }
                  var rows =
                      db.queryForList(
                          "SELECT * FROM fabric_identity WHERE issuer=? AND tenant=? AND user_id=?"
                              + " FOR UPDATE",
                          r.issuer(),
                          r.tenant(),
                          r.userId());
                  String subject;
                  if (rows.isEmpty()) {
                    subject = UUID.randomUUID().toString();
                    db.update(
                        "INSERT INTO"
                            + " fabric_identity(id,issuer,tenant,user_id,org_id,revision,command_hash,desired_state,action,status)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                        subject,
                        r.issuer(),
                        r.tenant(),
                        r.userId(),
                        r.orgId(),
                        r.revision(),
                        hash,
                        r.desiredState(),
                        r.action(),
                        initial(r));
                  } else {
                    var old = rows.get(0);
                    subject = (String) old.get("id");
                    long revision = ((Number) old.get("revision")).longValue();
                    if (!c.orgs().contains(old.get("org_id")))
                      throw new ApiError(403, "原组织不在调用方范围内");
                    if (r.revision() < revision
                        || r.revision() == revision && !hash.equals(old.get("command_hash")))
                      throw new ApiError(409, "身份修订号已过期或内容冲突");
                    if (r.revision() > revision) {
                      if ("DELETED".equals(old.get("desired_state")))
                        throw new ApiError(409, "已删除用户不能恢复");
                      String state = initial(r);
                      if ("SYNC".equals(r.action())
                          && "ACTIVE".equals(r.desiredState())
                          && r.orgId().equals(old.get("org_id"))
                          && "READY".equals(old.get("status"))) state = "READY";
                      db.update(
                          "UPDATE fabric_identity SET"
                              + " org_id=?,revision=?,command_hash=?,desired_state=?,action=?,status=?,attempts=0,last_error=NULL,next_attempt=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP"
                              + " WHERE id=?",
                          r.orgId(),
                          r.revision(),
                          hash,
                          r.desiredState(),
                          r.action(),
                          state,
                          subject);
                    }
                  }
                  db.update(
                      "INSERT INTO identity_request(caller_id,request_key,request_hash,identity_id)"
                          + " VALUES(?,?,?,?)",
                      c.id(),
                      key,
                      hash,
                      subject);
                  audit(
                      subject,
                      r.revision(),
                      c.id(),
                      r.action(),
                      Json.write(Map.of("orgId", r.orgId(), "desiredState", r.desiredState())));
                  return subject;
                });
        return get(c, id);
      } catch (DuplicateKeyException e) {
        if (attempt == 2) throw new ApiError(409, "并发身份请求冲突，请按原幂等键重试");
      }
    }
    throw new IllegalStateException();
  }

  private String initial(Command r) {
    if (requiresRevocation(r.action(), r.desiredState())) return "REVOCATION_PENDING";
    return "ACTIVE".equals(r.desiredState()) ? "PENDING" : r.desiredState();
  }

  static boolean requiresRevocation(String action, String desiredState) {
    return "REVOKE".equals(action) || "DELETED".equals(desiredState);
  }

  static void validate(IdentityProviderSettings.Client c, String key, Command r) {
    if (key == null
        || !key.matches("[A-Za-z0-9:_-]{16,128}")
        || r == null
        || !IdentityProviderSettings.id(r.userId())
        || !IdentityProviderSettings.id(r.orgId())
        || r.revision() < 1
        || r.desiredState() == null
        || !Set.of("ACTIVE", "DISABLED", "DELETED").contains(r.desiredState())
        || r.action() == null
        || !Set.of("SYNC", "ROTATE", "REVOKE").contains(r.action()))
      throw new ApiError(400, "无效身份请求");
    if (!c.issuer().equals(r.issuer())
        || !c.tenant().equals(r.tenant())
        || !c.orgs().contains(r.orgId())) throw new ApiError(403, "身份发行方、租户或组织越权");
    String operation =
        requiresRevocation(r.action(), r.desiredState())
            ? "revoke"
            : "ROTATE".equals(r.action())
                ? "rotate"
                : "ACTIVE".equals(r.desiredState()) ? "create" : "disable";
    if (!c.operations().contains(operation)) throw new ApiError(403, "身份操作未授权");
    if ("REVOKE".equals(r.action()) && "ACTIVE".equals(r.desiredState())
        || "ROTATE".equals(r.action()) && !"ACTIVE".equals(r.desiredState()))
      throw new ApiError(409, "生命周期操作与用户状态冲突");
  }

  public Map<String, Object> get(IdentityProviderSettings.Client c, String id) {
    if (!c.operations().contains("read")) throw new ApiError(403, "无查询权限");
    var rows =
        db.queryForList(
            "SELECT * FROM fabric_identity WHERE id=? AND issuer=? AND tenant=?",
            id,
            c.issuer(),
            c.tenant());
    if (rows.isEmpty()) throw new ApiError(404, "身份不存在");
    var row = rows.get(0);
    if (!c.orgs().contains(row.get("org_id"))) throw new ApiError(403, "身份组织越权");
    var out = new LinkedHashMap<String, Object>();
    out.put("identityId", row.get("id"));
    out.put("issuer", row.get("issuer"));
    out.put("tenant", row.get("tenant"));
    out.put("userId", row.get("user_id"));
    out.put("orgId", row.get("org_id"));
    out.put("revision", row.get("revision"));
    out.put("status", row.get("status"));
    out.put("desiredState", row.get("desired_state"));
    out.put("errorCode", row.get("last_error"));
    out.put("contractAuthorization", "NOT_GRANTED");
    out.put(
        "certificates",
        db.queryForList(
            "SELECT"
                + " version,org_id,msp_id,enrollment_id,state,fingerprint,certificate_pem,not_before,not_after,network_state,crl_state"
                + " FROM fabric_certificate WHERE identity_id=? ORDER BY version",
            id));
    out.put("certificateVersion", row.get("certificate_version"));
    if ("READY".equals(out.get("status"))) {
      var expiry =
          db.queryForList(
              "SELECT not_after FROM fabric_certificate WHERE identity_id=? AND version=?",
              id,
              row.get("certificate_version"));
      if (expiry.isEmpty()
          || !(expiry.get(0).get("not_after") instanceof java.util.Date date)
          || !date.toInstant().isAfter(java.time.Instant.now())) out.put("status", "EXPIRED");
    }
    return out;
  }

  public Map<String, Object> lifecycle(
      IdentityProviderSettings.Client c, String id, String key, long revision, String operation) {
    if (key == null
        || !key.matches("[A-Za-z0-9:_-]{16,128}")
        || !Set.of("retry", "rotate", "disable", "revoke").contains(operation))
      throw new ApiError(400, "无效生命周期请求");
    if (!c.operations().contains(operation)) throw new ApiError(403, "生命周期操作未授权");
    String hash =
        Json.sha(
            Json.write(Map.of("id", id, "revision", revision, "operation", operation))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    try {
      tx.executeWithoutResult(
          s -> {
            // One revision owner per issuer/tenant. Lock first so concurrent retries observe the
            // committed receipt.
            var locked =
                db.queryForList(
                    "SELECT id,action,desired_state FROM fabric_identity WHERE id=? FOR UPDATE", id);
            if (locked.isEmpty()) throw new ApiError(404, "身份不存在");
            var current = get(c, id);
            var task = locked.get(0);
            if ("retry".equals(operation)
                && requiresRevocation((String) task.get("action"), (String) task.get("desired_state"))
                && !c.operations().contains("revoke"))
              throw new ApiError(403, "吊销任务重试未授权");
            var prior =
                db.queryForList(
                    "SELECT request_hash FROM identity_request WHERE caller_id=? AND request_key=?",
                    c.id(),
                    key);
            if (!prior.isEmpty()) {
              if (!hash.equals(prior.get(0).get("request_hash")))
                throw new ApiError(409, "同一幂等键的请求内容不同");
              return;
            }
            if (revision != ((Number) current.get("revision")).longValue())
              throw new ApiError(409, "身份修订号已变化");
            if ("DELETED".equals(current.get("desiredState"))) throw new ApiError(409, "已删除用户不能操作");
            if ("retry".equals(operation)) {
              if (!Set.of("PENDING", "RETRY", "ISSUED", "NETWORK_PENDING", "REVOCATION_PENDING")
                  .contains(current.get("status"))) throw new ApiError(409, "身份当前不需要重试");
              db.update(
                  "UPDATE fabric_identity SET next_attempt=CURRENT_TIMESTAMP WHERE id=? AND"
                      + " revision=?",
                  id,
                  revision);
              audit(id, revision, c.id(), "RETRY", "{}");
            } else {
              if ("rotate".equals(operation) && !"ACTIVE".equals(current.get("desiredState")))
                throw new ApiError(409, "停用身份不能轮换");
              String internal =
                  "lifecycle:"
                      + Json.sha(
                          (c.id() + ":" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
              provision(
                  c,
                  internal,
                  new Command(
                      c.issuer(),
                      c.tenant(),
                      (String) current.get("userId"),
                      (String) current.get("orgId"),
                      revision + 1,
                      "rotate".equals(operation) ? "ACTIVE" : "DISABLED",
                      "rotate".equals(operation)
                          ? "ROTATE"
                          : "revoke".equals(operation) ? "REVOKE" : "SYNC"));
            }
            db.update(
                "INSERT INTO identity_request(caller_id,request_key,request_hash,identity_id)"
                    + " VALUES(?,?,?,?)",
                c.id(),
                key,
                hash,
                id);
          });
    } catch (DuplicateKeyException e) {
      throw new ApiError(409, "并发请求冲突，请按原幂等键重试");
    }
    return get(c, id);
  }

  public void audit(String id, long revision, String actor, String action, String detail) {
    db.update(
        "INSERT INTO identity_audit(id,identity_id,revision,actor,action,detail)"
            + " VALUES(?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        id,
        revision,
        actor,
        action,
        detail);
  }
}
