package com.bdemo.iam.provisioning;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.security.JwtAuthFilter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Local transactions persist desired state. Remote calls happen only after commit. */
@Service
public class IdentityTasks {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final String configFile;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public IdentityTasks(
      JdbcTemplate db,
      TransactionTemplate tx,
      ObjectMapper json,
      @Value("${iam.identity.config:}") String configFile) {
    this.db = db;
    this.tx = tx;
    this.json = json;
    this.configFile = configFile;
  }

  public record Config(
      String url, String tokenFile, String issuer, String tenant, Map<String, String> orgMap) {}

  private Config config() throws Exception {
    Config c = json.readValue(Files.readString(Path.of(configFile)), Config.class);
    URI uri = URI.create(c.url());
    if (!("https".equals(uri.getScheme())
            || "http".equals(uri.getScheme())
                && Set.of("127.0.0.1", "localhost").contains(uri.getHost()))
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || c.orgMap() == null) throw new IllegalStateException("Invalid identity endpoint");
    return c;
  }

  public void validateOrganization(String company) {
    try {
      if (!config().orgMap().containsKey(company))
        throw BizException.badRequest("公司未配置 Fabric 组织映射");
    } catch (BizException e) {
      throw e;
    } catch (Exception e) {
      throw new BizException(503, "身份供给配置不可用");
    }
  }

  // Called inside the same transaction as the user mutation, including logical deletion.
  public void sync(String userId, String reason) {
    var user =
        db.queryForMap(
            "SELECT company_code,status,deleted FROM iam.iam_user WHERE id=? FOR UPDATE", userId);
    String org = (String) user.get("company_code");
    String desired =
        ((Number) user.get("deleted")).intValue() != 0
            ? "DELETED"
            : "active".equals(user.get("status")) ? "ACTIVE" : "DISABLED";
    var rows =
        db.queryForList("SELECT user_id FROM iam.identity_task WHERE user_id=? FOR UPDATE", userId);
    if (rows.isEmpty())
      db.update(
          "INSERT INTO iam.identity_task(user_id,task_id,revision,org_code,desired_state,state)"
              + " VALUES(?,?,1,?,?, 'PENDING')",
          userId,
          UUID.randomUUID().toString(),
          org,
          desired);
    else
      db.update(
          "UPDATE iam.identity_task SET revision=revision+1,org_code=?,desired_state=?,action=CASE"
              + " WHEN ?='DISABLED' AND action='REVOKE' THEN 'REVOKE' ELSE 'SYNC'"
              + " END,state='PENDING',attempts=0,next_attempt=CURRENT_TIMESTAMP,public_result=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP"
              + " WHERE user_id=?",
          org,
          desired,
          desired,
          userId);
    audit(userId, reason);
  }

  public void audit(String target, String action) {
    var actor = JwtAuthFilter.currentUser();
    db.update(
        "INSERT INTO iam.iam_audit(id,kind,user_id,module,action,target,result)"
            + " VALUES(?,'operation',?,'identity',?,?,'success')",
        UUID.randomUUID().toString(),
        actor == null ? "SYSTEM" : actor.getUserId(),
        action,
        target);
  }

  public Map<String, Object> status(String userId) {
    var rows =
        db.queryForList(
            "SELECT"
                + " task_id,revision,state,desired_state,identity_id,public_result,last_error,attempts"
                + " FROM iam.identity_task WHERE user_id=?",
            userId);
    if (rows.isEmpty()) return Map.of("state", "NOT_PROVISIONED");
    var row = rows.get(0);
    var result = new LinkedHashMap<String, Object>();
    result.put("taskId", row.get("task_id"));
    result.put("state", row.get("state"));
    result.put("revision", row.get("revision"));
    result.put("desiredState", row.get("desired_state"));
    result.put("identityId", row.get("identity_id"));
    result.put("errorCode", row.get("last_error"));
    if (row.get("public_result") instanceof String value) {
      try {
        var identity = json.readValue(value, new TypeReference<Map<String, Object>>() {});
        if ("READY".equals(identity.get("status"))) {
          boolean valid = false;
          if (identity.get("certificates") instanceof java.util.List<?> certificates)
            for (Object item : certificates)
              if (item instanceof Map<?, ?> cert
                  && Objects.equals(cert.get("version"), identity.get("certificateVersion"))) {
                Object expiry = cert.get("not_after");
                java.time.Instant instant =
                    expiry instanceof Number n
                        ? java.time.Instant.ofEpochMilli(n.longValue())
                        : java.time.Instant.parse(String.valueOf(expiry));
                valid = instant.isAfter(java.time.Instant.now());
              }
          if (!valid) {
            identity.put("status", "EXPIRED");
            result.put("state", "EXPIRED");
          }
        }
        result.put("identity", identity);
      } catch (Exception e) {
        throw new IllegalStateException("Invalid persisted identity result");
      }
    }
    return result;
  }

  public Map<String, Object> operate(String userId, String action, String key) {
    if (key == null || !key.matches("[A-Za-z0-9:_-]{16,128}"))
      throw BizException.badRequest("需要有效 Idempotency-Key");
    tx.executeWithoutResult(
        s -> {
          var user =
              db.queryForMap(
                  "SELECT status,deleted FROM iam.iam_user WHERE id=? FOR UPDATE", userId);
          var prior =
              db.queryForList(
                  "SELECT user_id,action FROM iam.identity_operation_request WHERE request_key=?",
                  key);
          if (!prior.isEmpty()) {
            if (!userId.equals(prior.get(0).get("user_id"))
                || !action.equals(prior.get(0).get("action")))
              throw BizException.conflict("相同幂等键的请求内容不同");
            return;
          }
          if (((Number) user.get("deleted")).intValue() != 0) throw BizException.conflict("用户已删除");
          if (!Set.of("retry", "rotate", "revoke").contains(action))
            throw BizException.badRequest("无效操作");
          if ("revoke".equals(action) && "active".equals(user.get("status")))
            throw BizException.conflict("请先停用用户，再撤销证书");
          if ("rotate".equals(action) && !"active".equals(user.get("status")))
            throw BizException.conflict("停用用户不能轮换为可用身份");
          int updated;
          if ("retry".equals(action))
            updated =
                db.update(
                    "UPDATE iam.identity_task SET"
                        + " state='PENDING',attempts=0,next_attempt=CURRENT_TIMESTAMP WHERE"
                        + " user_id=? AND state IN ('PENDING','RETRY')",
                    userId);
          else
            updated =
                db.update(
                    "UPDATE iam.identity_task SET"
                        + " revision=revision+1,action=?,state='PENDING',public_result=NULL,last_error=NULL,attempts=0,next_attempt=CURRENT_TIMESTAMP"
                        + " WHERE user_id=?",
                    action.toUpperCase(Locale.ROOT),
                    userId);
          if (updated == 0) throw BizException.conflict("没有可操作的身份任务");
          db.update(
              "INSERT INTO iam.identity_operation_request(request_key,user_id,action)"
                  + " VALUES(?,?,?)",
              key,
              userId,
              action);
          audit(userId, "IDENTITY_" + action.toUpperCase(Locale.ROOT));
        });
    return status(userId);
  }

  @Scheduled(fixedDelayString = "${iam.identity.poll-ms:2000}")
  public void recover() {
    if (configFile.isBlank()) return;
    for (var row :
        db.queryForList(
            "SELECT user_id FROM iam.identity_task WHERE state IN"
                + " ('PENDING','RETRY','READY','CRL_PENDING') AND next_attempt<=CURRENT_TIMESTAMP"
                + " AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP) ORDER BY"
                + " next_attempt LIMIT 10")) process((String) row.get("user_id"));
  }

  public void process(String userId) {
    process(userId, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4));
  }

  public void process(String userId, long deadline) {
    if (System.nanoTime() >= deadline) return;
    // This method must never be invoked with the user transaction open.
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("Remote identity work cannot run inside a user transaction");
    String lease = UUID.randomUUID().toString();
    var task =
        tx.execute(
            s -> {
              if (db.update(
                      "UPDATE iam.identity_task SET lease_token=?,lease_until=CURRENT_TIMESTAMP +"
                          + " INTERVAL '30 seconds' WHERE user_id=? AND state IN"
                          + " ('PENDING','RETRY','READY','CRL_PENDING') AND (lease_until IS NULL OR"
                          + " lease_until<CURRENT_TIMESTAMP)",
                      lease,
                      userId)
                  != 1) return null;
              return db.queryForMap("SELECT * FROM iam.identity_task WHERE user_id=?", userId);
            });
    if (task == null) return;
    long revision = ((Number) task.get("revision")).longValue();
    try {
      Config c = config();
      String org = c.orgMap().get((String) task.get("org_code"));
      if (org == null) throw new IllegalStateException("ORG_MAPPING_MISSING");
      var body =
          Map.of(
              "issuer",
              c.issuer(),
              "tenant",
              c.tenant(),
              "userId",
              userId,
              "orgId",
              org,
              "revision",
              revision,
              "desiredState",
              task.get("desired_state"),
              "action",
              task.get("action"));
      var request =
          HttpRequest.newBuilder(URI.create(c.url() + "/api/v1/identities"))
              .timeout(
                  Duration.ofNanos(
                      Math.max(
                          1,
                          Math.min(Duration.ofSeconds(4).toNanos(), deadline - System.nanoTime()))))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .header("Authorization", "Bearer " + Files.readString(Path.of(c.tokenFile())).trim())
              .header("Idempotency-Key", task.get("task_id") + ":" + revision)
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (!response.headers().firstValue("content-type").orElse("").contains("application/json")
          || response.body().length() > 131072)
        throw new IllegalStateException("TRUST_INVALID_RESPONSE");
      if (response.statusCode() != 200 && response.statusCode() != 202)
        throw new IllegalStateException("TRUST_HTTP_" + response.statusCode());
      var result = json.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
      if (!userId.equals(result.get("userId"))
          || !org.equals(result.get("orgId"))
          || !c.issuer().equals(result.get("issuer"))
          || !c.tenant().equals(result.get("tenant"))
          || !(result.get("revision") instanceof Number n)
          || n.longValue() != revision) throw new IllegalStateException("TRUST_IDENTITY_MISMATCH");
      String state = (String) result.get("status");
      boolean done =
          "ACTIVE".equals(task.get("desired_state"))
              ? "READY".equals(state)
              : Set.of("DISABLED", "DELETED", "REVOKED", "CRL_PENDING").contains(state);
      if ("REVOKE".equals(task.get("action")))
        done = Set.of("REVOKED", "CRL_PENDING").contains(state);
      final boolean completed = done;
      String finalState = done ? ("READY".equals(state) ? "READY" : state) : "RETRY";
      tx.executeWithoutResult(
          s -> {
            int updated =
                db.update(
                    "UPDATE iam.identity_task SET"
                        + " state=?,identity_id=?,public_result=?,last_error=NULL,lease_token=NULL,lease_until=NULL,next_attempt=CURRENT_TIMESTAMP"
                        + " + INTERVAL '15 seconds',updated_at=CURRENT_TIMESTAMP WHERE user_id=?"
                        + " AND revision=? AND lease_token=?",
                    finalState,
                    result.get("identityId"),
                    jsonString(result),
                    userId,
                    revision,
                    lease);
            if (updated == 1 && completed && !finalState.equals(task.get("state")))
              audit(userId, "IDENTITY_" + finalState);
          });
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      // Never persist remote bodies, certificate secrets, bearer tokens or exception messages.
      db.update(
          "UPDATE iam.identity_task SET"
              + " state='RETRY',last_error='PROVISIONING_UNAVAILABLE',attempts=attempts+1,lease_token=NULL,lease_until=NULL,next_attempt=CURRENT_TIMESTAMP"
              + " + INTERVAL '30 seconds' WHERE user_id=? AND revision=? AND lease_token=?",
          userId,
          revision,
          lease);
    } finally {
      db.update(
          "UPDATE iam.identity_task SET lease_token=NULL,lease_until=NULL WHERE user_id=? AND"
              + " lease_token=?",
          userId,
          lease);
    }
  }

  private String jsonString(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
