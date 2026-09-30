package com.bdemo.iam.provisioning;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.user.UserService;
import com.bdemo.iam.user.domain.User;
import com.bdemo.iam.user.dto.UserUpsertRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UserCreation {
  private final UserService users;
  private final IdentityTasks tasks;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final String pepper;

  public UserCreation(
      UserService users,
      IdentityTasks tasks,
      JdbcTemplate db,
      TransactionTemplate tx,
      ObjectMapper json,
      @org.springframework.beans.factory.annotation.Value("${iam.identity.idempotency-secret:}")
          String pepper) {
    this.users = users;
    this.tasks = tasks;
    this.db = db;
    this.tx = tx;
    this.json = json;
    this.pepper = pepper;
  }

  public User create(String key, UserUpsertRequest r) {
    if (key == null || !key.matches("[A-Za-z0-9:_-]{16,128}"))
      throw BizException.badRequest("需要有效 Idempotency-Key");
    if (r.blockchainId() != null || r.blockchainAddress() != null)
      throw BizException.badRequest("Fabric 身份由服务端供给");
    String hash = hash(r);
    String id;
    try {
      id =
          tx.execute(
              s -> {
                var prior =
                    db.queryForList(
                        "SELECT request_hash,user_id FROM iam.user_creation_request WHERE"
                            + " request_key=?",
                        key);
                if (!prior.isEmpty()) return replay(prior.get(0), hash);
                var user =
                    users.create(
                        r.username(),
                        r.name(),
                        r.phone(),
                        r.email(),
                        r.password(),
                        r.roleIds(),
                        r.orgCodes(),
                        r.status(),
                        null,
                        null);
                db.update(
                    "INSERT INTO iam.user_creation_request(request_key,request_hash,user_id)"
                        + " VALUES(?,?,?)",
                    key,
                    hash,
                    user.getId());
                return user.getId();
              });
    } catch (DuplicateKeyException e) {
      var prior =
          db.queryForList(
              "SELECT request_hash,user_id FROM iam.user_creation_request WHERE request_key=?",
              key);
      if (prior.isEmpty()) throw BizException.conflict("用户名已存在");
      id = replay(prior.get(0), hash);
    } catch (BizException e) {
      if (e.getHttpStatus() != 409) throw e;
      var prior =
          db.queryForList(
              "SELECT request_hash,user_id FROM iam.user_creation_request WHERE request_key=?",
              key);
      if (prior.isEmpty()) throw e;
      id = replay(prior.get(0), hash);
    }
    // The committed durable task survives a timeout here. Each HTTP request is bounded to 4 s.
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    tasks.process(id, deadline);
    while (System.nanoTime() < deadline && !"READY".equals(tasks.status(id).get("state"))) {
      try {
        Thread.sleep(250);
        tasks.process(id, deadline);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return users.get(id);
  }

  private String replay(java.util.Map<String, Object> row, String hash) {
    if (!hash.equals(row.get("request_hash"))) throw BizException.conflict("相同幂等键的请求内容不同");
    return (String) row.get("user_id");
  }

  private String hash(Object r) {
    try {
      if (pepper.length() < 32)
        throw new IllegalStateException("Idempotency secret not configured");
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(json.writeValueAsBytes(r)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
