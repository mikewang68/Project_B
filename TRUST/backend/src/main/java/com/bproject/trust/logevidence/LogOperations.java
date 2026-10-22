package com.bproject.trust.logevidence;

import com.bproject.trust.identity.PlatformPrincipal;
import com.bproject.trust.ingress.IngressError;
import com.bproject.trust.shared.json.Json;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LogOperations {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final LogSettings settings;

  @org.springframework.beans.factory.annotation.Autowired
  private com.bproject.trust.identity.IntegrationSettings identitySettings;

  @org.springframework.beans.factory.annotation.Autowired
  private com.bproject.trust.identity.CurrentIdentity currentIdentity;

  public LogOperations(JdbcTemplate db, TransactionTemplate tx, LogSettings settings) {
    this.db = db;
    this.tx = tx;
    this.settings = settings;
  }

  public record PageAction(
      String requestId,
      String action,
      String menuId,
      String objectId,
      String path,
      String summary) {}

  public record PageResult(String outcome, String summary) {}

  public record Controls(
      Boolean archivePaused, Boolean chainPaused, String reason, Long expectedRevision) {
    public Controls(Boolean archivePaused, Boolean chainPaused, String reason) {
      this(archivePaused, chainPaused, reason, null);
    }
  }

  public record RetryDelivery(String reason) {}

  public static PlatformPrincipal principal(Authentication a) {
    if (a == null || !a.isAuthenticated() || !(a.getPrincipal() instanceof PlatformPrincipal p))
      throw new IngressError(403, "VERIFIED_IDENTITY_REQUIRED", "需要真实 IAM 身份");
    return p;
  }

  private PlatformPrincipal verified(Authentication a) {
    if (identitySettings != null && !"real".equals(identitySettings.read().iamMode()))
      throw new IngressError(403, "VERIFIED_IDENTITY_REQUIRED", "需要真实 IAM 身份");
    return principal(a);
  }

  /** Page ownership is local authentication, not an external IAM assurance claim. */
  public PlatformPrincipal pagePrincipal(Authentication a) {
    if (identitySettings == null || "real".equals(identitySettings.read().iamMode()))
      return verified(a);
    if (a == null
        || !a.isAuthenticated()
        || a instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
      throw new IngressError(403, "AUTHENTICATION_REQUIRED", "需要已登录身份");
    if (a.getPrincipal() instanceof PlatformPrincipal p)
      return new PlatformPrincipal("simulated:" + p.userId(), p.username(), p.orgId());
    if (!identitySettings.localLoginEnabled(identitySettings.read()) || currentIdentity == null)
      throw new IngressError(403, "LOCAL_IDENTITY_DISABLED", "本地身份未启用");
    try {
      return new PlatformPrincipal("local:" + a.getName(), a.getName(), currentIdentity.org(a));
    } catch (org.springframework.dao.EmptyResultDataAccessException missing) {
      throw new IngressError(403, "LOCAL_IDENTITY_DISABLED", "本地身份不存在或已停用");
    }
  }

  public void verifyPageAction(Authentication a, String id) {
    var p = pagePrincipal(a);
    UUID.fromString(id);
    var rows = db.queryForList("SELECT * FROM audit_page_action_read WHERE id=?", id);
    if (rows.isEmpty()) throw LogEvidenceService.missing();
    pageOwner(rows.get(0), p);
  }

  public Map<String, Object> page(Authentication a, PageAction input) {
    var p = pagePrincipal(a);
    UUID.fromString(input.requestId());
    validatePage(input);
    String body = Json.write(input);
    try {
      return tx.execute(
          s -> {
            var rows =
                db.queryForList(
                    "SELECT * FROM audit_page_action_read WHERE id=?", input.requestId());
            if (!rows.isEmpty()) {
              pageOwner(rows.get(0), p);
              if (!body.equals(rows.get(0).get("request_json"))) throw conflict();
              return recorded(input.requestId());
            }
            db.update(
                "INSERT INTO audit_page_action(id,org_id,actor,request_json,identity_mode)"
                    + " VALUES(?,?,?,?,?)",
                input.requestId(),
                p.orgId(),
                p.userId(),
                body,
                pageMode(p));
            enqueue(
                p.orgId(),
                p.userId(),
                input.action(),
                input.objectId(),
                "STARTED",
                "CLIENT",
                input.menuId(),
                "",
                input.path(),
                input.summary(),
                input.requestId());
            return recorded(input.requestId());
          });
    } catch (DuplicateKeyException e) {
      var row =
          db.queryForMap("SELECT * FROM audit_page_action_read WHERE id=?", input.requestId());
      pageOwner(row, p);
      if (!body.equals(row.get("request_json"))) throw conflict();
      return recorded(input.requestId());
    }
  }

  public Map<String, Object> result(Authentication a, String id, PageResult input) {
    var p = pagePrincipal(a);
    UUID.fromString(id);
    if (!Set.of("SUCCESS", "FAILED", "DENIED").contains(input.outcome()))
      throw new IllegalArgumentException();
    LogEvent.bounded(input.summary(), 512);
    return tx.execute(
        s -> {
          var rows =
              com.bproject.trust.shared.RetentionLock.rows(db, "audit_page_action", "id=?", id);
          if (rows.isEmpty()) throw LogEvidenceService.missing();
          var row = rows.get(0);
          pageOwner(row, p);
          String text = Json.write(input);
          if (row.get("result_json") != null) {
            if (!text.equals(row.get("result_json"))) throw conflict();
            return recorded(id);
          }
          var request = Json.map((String) row.get("request_json"));
          enqueue(
              p.orgId(),
              p.userId(),
              (String) request.get("action"),
              (String) request.get("objectId"),
              input.outcome(),
              "CLIENT",
              (String) request.get("menuId"),
              "",
              (String) request.get("path"),
              input.summary(),
              id);
          db.update(
              "UPDATE audit_page_action_read SET"
                  + " result_json=?,change_actor=?,change_operation='RESULT',change_reason=? WHERE"
                  + " id=?",
              text,
              p.userId(),
              input.summary(),
              id);
          return recorded(id);
        });
  }

  void validatePage(PageAction p) {
    new LogEvent(
        1,
        p.requestId(),
        Instant.now(),
        "TRUST",
        "CHECK",
        "CHECK",
        "",
        "AUTHENTICATED",
        p.menuId(),
        p.action(),
        p.objectId(),
        "STARTED",
        "",
        p.path(),
        p.requestId(),
        0,
        "CLIENT",
        p.summary());
    if (p.path() == null || !p.path().startsWith("/")) throw new IllegalArgumentException();
  }

  static Map<String, Object> recorded(String id) {
    return Map.of("requestId", id, "state", "RECORDED");
  }

  static IngressError conflict() {
    return new IngressError(409, "ACTION_CONFLICT", "操作编号已对应其他记录");
  }

  private String pageMode(PlatformPrincipal p) {
    if (identitySettings == null || "real".equals(identitySettings.read().iamMode())) return "real";
    return p.userId().startsWith("local:") ? "local" : "simulated";
  }

  void pageOwner(Map<String, Object> row, PlatformPrincipal p) {
    if (!pageMode(p).equals(Objects.toString(row.get("identity_mode"), "real"))
        || !p.orgId().equals(row.get("org_id"))
        || !p.userId().equals(row.get("actor"))) throw LogEvidenceService.missing();
  }

  public Map<String, Object> controls(Authentication a) {
    var p = verified(a);
    var c = settings.read();
    var row = db.queryForMap("SELECT * FROM audit_log_settings_read WHERE id=1");
    var counts =
        db.queryForMap(
            "SELECT COUNT(*) AS pending,MIN(created_at) AS oldest FROM audit_delivery_read WHERE"
                + " state<>'DELIVERED' AND org_id=?",
            p.orgId());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(
        "canManage",
        !com.bproject.trust.identity.ReadOnlyPolicy.restricted(a)
            && c.managerUserIds().contains(p.userId()));
    result.put("archivePaused", row.get("archive_paused"));
    result.put("revision", row.get("retention_revision"));
    result.put("canReadHistory", c.managerUserIds().contains(p.userId()));
    result.put("chainPaused", row.get("chain_paused"));
    result.put("configured", c.enabled());
    result.put("collectionEnabled", true);
    long deliveryPending = ((Number) counts.get("pending")).longValue();
    long failures =
        db.queryForObject(
            "SELECT COUNT(*) FROM audit_delivery_read WHERE last_error IS NOT NULL AND"
                + " state<>'DELIVERED' AND org_id=?",
            Long.class,
            p.orgId());
    var args = new ArrayList<Object>();
    String scope = "1=0";
    if (c.managerUserIds().contains(p.userId())) scope = "1=1";
    else if (c.delivery().orgScopes().containsKey(p.orgId())) {
      var ownedScope = c.delivery().orgScopes().get(p.orgId());
      scope = "l.domain_id=? AND l.zone_id=? AND l.app_code=?";
      args.add(ownedScope.domainId());
      args.add(ownedScope.zoneId());
      args.add(ownedScope.appCode());
    }
    var pipeline =
        db.queryForMap(
            "SELECT COUNT(CASE WHEN l.archive_state<>'STORED' THEN 1 END) AS"
                + " archive_pending,COUNT(CASE WHEN l.archive_state='STORED' AND (t.state IS NULL"
                + " OR t.state<>'DONE' OR e.chain_state<>'COMMITTED') THEN 1 END) AS"
                + " chain_pending,COUNT(CASE WHEN l.archive_state='FAILED' OR t.state='FAILED' THEN"
                + " 1 END) AS failed,MIN(CASE WHEN t.state IS NULL OR t.state<>'DONE' OR"
                + " e.chain_state<>'COMMITTED' THEN l.created_at END) AS oldest FROM"
                + " audit_evidence_read l LEFT JOIN events_read e ON e.id=l.chain_event_id LEFT"
                + " JOIN tasks_read t ON t.event_id=e.id WHERE "
                + scope,
            args.toArray());
    long archivePending = ((Number) pipeline.get("archive_pending")).longValue(),
        chainPending = ((Number) pipeline.get("chain_pending")).longValue();
    result.put("deliveryPendingCount", deliveryPending);
    result.put("archivePendingCount", archivePending);
    result.put("chainPendingCount", chainPending);
    result.put("pendingCount", deliveryPending + archivePending + chainPending);
    result.put("failedCount", failures + ((Number) pipeline.get("failed")).longValue());
    java.sql.Timestamp oldest = (java.sql.Timestamp) counts.get("oldest"),
        other = (java.sql.Timestamp) pipeline.get("oldest");
    if (oldest == null || other != null && other.before(oldest)) oldest = other;
    result.put("oldestPendingAt", oldest == null ? null : oldest.toInstant().toString());
    result.put("settingsUpdatedAt", row.get("updated_at"));
    var errors =
        db.queryForList(
            "SELECT last_error FROM audit_delivery_read WHERE last_error IS NOT NULL AND org_id=?"
                + " ORDER BY created_at DESC LIMIT 1",
            p.orgId());
    result.put(
        "lastError",
        ((Number) pipeline.get("failed")).longValue() > 0
            ? "ARCHIVE_OR_CHAIN_ACTION_REQUIRED"
            : errors.isEmpty() ? null : errors.get(0).get("last_error"));
    return result;
  }

  public Map<String, Object> controls(Authentication a, Controls input) {
    var p = verified(a);
    if (!settings.read().managerUserIds().contains(p.userId())) {
      rejected(a, "PATCH", "/api/v1/audit/settings");
      throw new IngressError(403, "LOG_MANAGER_REQUIRED", "无日志设置管理权限");
    }
    if (input.archivePaused() == null || input.chainPaused() == null)
      throw new IllegalArgumentException();
    if (input.expectedRevision() == null)
      throw new IngressError(409, "VERSION_REQUIRED", "请刷新设置后再保存，修改须包含当前版本");
    String reason = LogEvent.bounded(input.reason(), 300);
    if (reason.isBlank()) throw new IllegalArgumentException();
    return tx.execute(
        t -> {
          var previous =
              com.bproject.trust.shared.RetentionLock.row(db, "audit_log_settings", "id=1");
          if (input.expectedRevision() != ((Number) previous.get("retention_revision")).longValue())
            throw new IngressError(409, "VERSION_CONFLICT", "设置已变化，请刷新后再保存");
          String summary =
              "archivePaused="
                  + previous.get("archive_paused")
                  + "->"
                  + input.archivePaused()
                  + ", chainPaused="
                  + previous.get("chain_paused")
                  + "->"
                  + input.chainPaused()
                  + "; "
                  + reason;
          enqueue(
              p.orgId(),
              "iam:" + p.userId(),
              "UPDATE",
              "audit-settings",
              "SUCCESS",
              "BUSINESS",
              "operations",
              "PATCH",
              "/api/v1/audit/settings",
              summary,
              correlation(p));
          db.update(
              "UPDATE audit_log_settings_read SET"
                  + " archive_paused=?,chain_paused=?,updated_at=CURRENT_TIMESTAMP,change_actor=?,change_operation='UPDATE',change_reason=?"
                  + " WHERE id=1",
              input.archivePaused(),
              input.chainPaused(),
              "iam:" + p.userId(),
              reason);
          return controls(a);
        });
  }

  public void requireSettingsHistory(Authentication a) {
    var p = verified(a);
    if (!settings.read().managerUserIds().contains(p.userId()))
      throw new IngressError(403, "LOG_MANAGER_REQUIRED", "无日志设置历史查看权限");
  }

  public void enqueue(
      String org,
      String actor,
      String action,
      String object,
      String outcome,
      String kind,
      String menu,
      String method,
      String path,
      String summary,
      String trace) {
    // Simulated IAM identities never acquire authenticated external audit provenance, even if
    // somebody accidentally configures a delivery scope for their original organisation.
    if (identitySettings != null && !"real".equals(identitySettings.read().iamMode())) {
      org = "SIMULATED_LOCAL";
      actor = "";
    }
    // Validate the producer envelope before durable insertion; no browser identity is trusted.
    new LogEvent(
        1,
        "CHECK",
        Instant.now(),
        "TRUST",
        "CHECK",
        "CHECK",
        actor,
        "AUTHENTICATED",
        menu,
        action,
        object,
        outcome,
        method,
        path,
        trace,
        0,
        kind,
        summary);
    db.update(
        "INSERT INTO"
            + " audit_delivery(id,org_id,actor,action,object_id,outcome,source_kind,menu_id,method,path,summary,trace_id,occurred_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        org,
        optionalText(actor),
        action,
        optionalText(object),
        outcome,
        kind,
        optionalText(menu),
        optionalText(method),
        optionalText(path),
        optionalText(summary),
        optionalText(trace),
        Instant.now().toString());
  }

  private static String optionalText(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  public void rejected(
      org.springframework.security.core.Authentication auth, String method, String path) {
    PlatformPrincipal p =
        auth != null && auth.getPrincipal() instanceof PlatformPrincipal identity ? identity : null;
    // An unverified request is retained under a dedicated local scope, never assigned a
    // browser-supplied organisation.
    enqueue(
        p == null ? "UNVERIFIED_SECURITY" : p.orgId(),
        p == null ? "" : p.userId(),
        "EXECUTE",
        "",
        "DENIED",
        "REQUEST",
        "",
        method,
        path,
        "Access denied",
        UUID.randomUUID().toString());
  }

  public List<Map<String, Object>> deliveries(Authentication a) {
    var p = verified(a);
    if (!settings.read().managerUserIds().contains(p.userId()))
      throw new IngressError(403, "LOG_MANAGER_REQUIRED", "无日志投递管理权限");
    return db.queryForList(
        "SELECT id,action,outcome,state,attempts,last_error,created_at FROM audit_delivery_read"
            + " WHERE state<>'DELIVERED' ORDER BY created_at LIMIT 200");
  }

  public Map<String, Object> retryDelivery(Authentication a, String id, RetryDelivery input) {
    var p = verified(a);
    if (!settings.read().managerUserIds().contains(p.userId())) {
      rejected(a, "POST", "/api/v1/audit/delivery/retry");
      throw new IngressError(403, "LOG_MANAGER_REQUIRED", "无日志投递管理权限");
    }
    UUID.fromString(id);
    String reason = LogEvent.bounded(input.reason(), 300);
    if (reason.isBlank()) throw new IllegalArgumentException();
    return tx.execute(
        t -> {
          var rows = com.bproject.trust.shared.RetentionLock.rows(db, "audit_delivery", "id=?", id);
          if (rows.isEmpty()) throw LogEvidenceService.missing();
          boolean queued = "FAILED".equals(rows.get(0).get("state"));
          if (queued) {
            db.update(
                "UPDATE audit_delivery_read SET"
                    + " state='PENDING',attempts=0,last_error=NULL,next_at=CURRENT_TIMESTAMP,change_actor=?,change_operation='RETRY',change_reason=?"
                    + " WHERE id=?",
                "iam:" + p.userId(),
                reason,
                id);
            enqueue(
                p.orgId(),
                "iam:" + p.userId(),
                "EXECUTE",
                id,
                "SUCCESS",
                "BUSINESS",
                "operations",
                "POST",
                "/api/v1/audit/delivery/retry",
                reason,
                correlation(p));
          }
          return Map.of("id", id, "queued", queued);
        });
  }

  @Transactional
  public void business(String org, String actor, String action, String object) {
    // Existing callers retain their business transaction. Log transport is asynchronous.
    // Reads are logged by the HTTP request audit. Do not duplicate periodic reads as BUSINESS
    // changes.
    if (action.endsWith("_QUERY") || action.endsWith("_READ")) return;
    String normalized =
        action.contains("RETRY") || action.contains("VERIFY")
            ? "EXECUTE"
            : action.contains("EXPORT")
                ? "EXPORT"
                : action.contains("DOWNLOAD")
                    ? "DOWNLOAD"
                    : action.contains("UPLOAD")
                        ? "UPLOAD"
                        : action.contains("IMPORT")
                            ? "IMPORT"
                            : action.contains("DELETE")
                                ? "DELETE"
                                : action.contains("CREATE") || action.contains("SUBMIT")
                                    ? "CREATE"
                                    : "UPDATE";
    enqueue(
        org,
        actor,
        normalized,
        Objects.toString(object, ""),
        "SUCCESS",
        "BUSINESS",
        "",
        "",
        "",
        LogEvent.bounded(action, 120),
        correlation(null));
  }

  private String correlation(PlatformPrincipal principal) {
    var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    if (!(attrs
        instanceof org.springframework.web.context.request.ServletRequestAttributes request))
      return "";
    if (request.getRequest().getAttribute("TRUST_VERIFIED_PAGE_ACTION") instanceof String id)
      return id;
    String id = request.getRequest().getHeader("X-TRUST-Page-Action-Id");
    if (principal == null || id == null) return "";
    UUID.fromString(id);
    var rows = db.queryForList("SELECT * FROM audit_page_action_read WHERE id=?", id);
    if (rows.isEmpty()) throw LogEvidenceService.missing();
    pageOwner(rows.get(0), principal);
    return id;
  }
}
