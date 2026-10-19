package com.bproject.trust.logevidence;

import com.bproject.trust.ingress.*;
import com.bproject.trust.ports.EvidenceStorage;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LogEvidenceService {
  final JdbcTemplate db;
  final TransactionTemplate tx;
  final LogCipher cipher;
  final GenericEventService generic;
  final EvidenceIngressSettings ingress;
  final EvidenceStorage storage;

  public LogEvidenceService(
      JdbcTemplate db,
      TransactionTemplate tx,
      LogCipher cipher,
      GenericEventService generic,
      EvidenceIngressSettings ingress,
      EvidenceStorage storage) {
    this.db = db;
    this.tx = tx;
    this.cipher = cipher;
    this.generic = generic;
    this.ingress = ingress;
    this.storage = storage;
  }

  public record Result(int status, Map<String, Object> receipt) {}

  public Result submit(LogSettings.Source source, LogInput input) {
    source.require("submit");
    if (!source
        .scopes()
        .contains(
            new LogSettings.Scope(
                input.event().domainId(), input.event().zoneId(), input.event().appCode())))
      throw new IngressError(403, "LOG_SCOPE_DENIED", "日志作用域未获授权");
    try {
      return tx.execute(
          s -> {
            var prior =
                db.queryForList("SELECT * FROM audit_evidence WHERE event_key=?", input.eventKey());
            if (!prior.isEmpty()) return replay(source, input, prior.get(0));
            var encrypted =
                cipher.encrypt(input.eventKey(), input.payloadHash(), input.canonical());
            db.update(
                "INSERT INTO"
                    + " audit_evidence(event_key,source_id,event_id,domain_id,zone_id,app_code,payload_hash,key_id,ciphertext,ciphertext_hash,generic_source_id,occurred_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                input.eventKey(),
                source.id(),
                input.eventId(),
                input.event().domainId(),
                input.event().zoneId(),
                input.event().appCode(),
                input.payloadHash(),
                encrypted.keyId(),
                encrypted.ciphertext(),
                encrypted.ciphertextHash(),
                source.genericSourceId(),
                input.event().occurredAt().toString());
            return new Result(202, receipt(source, input.eventKey()));
          });
    } catch (DuplicateKeyException e) {
      return replay(source, input, owned(source, input.eventKey()));
    }
  }

  private Result replay(LogSettings.Source source, LogInput input, Map<String, Object> row) {
    checkOwner(source, row);
    if (!input.payloadHash().equals(row.get("payload_hash")))
      throw new IngressError(409, "LOG_CONFLICT", "同一日志键已登记不同内容");
    return new Result(200, receipt(source, input.eventKey()));
  }

  Map<String, Object> owned(LogSettings.Source source, String key) {
    if (key == null || !key.matches("[a-f0-9]{64}")) throw missing();
    var row =
        db.queryForList("SELECT * FROM audit_evidence WHERE event_key=?", key).stream()
            .findFirst()
            .orElseThrow(LogEvidenceService::missing);
    checkOwner(source, row);
    return row;
  }

  void checkOwner(LogSettings.Source source, Map<String, Object> row) {
    if (!source.id().equals(row.get("source_id"))) throw missing();
    source.scope(
        (String) row.get("domain_id"), (String) row.get("zone_id"), (String) row.get("app_code"));
  }

  public Map<String, Object> receipt(LogSettings.Source source, String key) {
    source.require("read");
    var row = owned(source, key);
    var result = new LinkedHashMap<String, Object>();
    String state = "ACCEPTED", chain = "PENDING", transaction = "";
    if ("FAILED".equals(row.get("archive_state"))) state = "FAILED";
    if (row.get("chain_event_id") != null) {
      var r = generic.receipt(projectionSource(row), (String) row.get("chain_event_id"));
      chain = (String) r.get("status");
      String txId = (String) r.get("txId");
      if ("COMMITTED".equals(chain) && txId != null && !txId.isBlank()) {
        state = "COMMITTED";
        transaction = txId;
      } else if ("ACTION_REQUIRED".equals(chain)) state = "FAILED";
      else state = "PENDING";
    } else if ("STORED".equals(row.get("archive_state"))) state = "PENDING";
    result.put("eventKey", key);
    result.put("payloadHash", row.get("payload_hash"));
    result.put("state", state);
    result.put("transactionId", transaction);
    result.put("archiveState", row.get("archive_state"));
    result.put("chainState", chain);
    result.put("errorCode", row.get("last_error"));
    return result;
  }

  EvidenceIngressSettings.Source projectionSource(Map<String, Object> row) {
    return ingress.processingSources().stream()
        .filter(s -> s.id().equals(row.get("generic_source_id")))
        .findFirst()
        .orElseThrow(() -> new IngressError(503, "LOG_CHAIN_NOT_CONFIGURED", "日志链投影来源未配置"));
  }

  public Result retry(LogSettings.Source source, String key) {
    source.require("retry");
    return tx.execute(
        t -> {
          var row = owned(source, key);
          row = db.queryForMap("SELECT * FROM audit_evidence WHERE event_key=? FOR UPDATE", key);
          if (row.get("lease_until") instanceof java.sql.Timestamp until
              && until.toInstant().isAfter(java.time.Instant.now()))
            throw new IngressError(409, "LOG_TASK_RUNNING", "日志任务正在处理");
          boolean queued = false;
          if (row.get("chain_event_id") != null)
            queued =
                Boolean.TRUE.equals(
                    generic
                        .retry(projectionSource(row), (String) row.get("chain_event_id"))
                        .receipt()
                        .get("queued"));
          if ("FAILED".equals(row.get("archive_state"))) {
            db.update(
                "UPDATE audit_evidence SET"
                    + " archive_state='PENDING',attempts=0,last_error=NULL,next_at=CURRENT_TIMESTAMP"
                    + " WHERE event_key=?",
                key);
            queued = true;
          }
          var out = receipt(source, key);
          out.put("queued", queued);
          return new Result(queued ? 202 : 200, out);
        });
  }

  public Map<String, Object> original(LogSettings.Source source, String key, boolean fromArchive) {
    source.require("restore");
    var row = owned(source, key);
    String encrypted = (String) row.get("ciphertext");
    access(source, key, "RESTORE", "STARTED");
    if (fromArchive) {
      if (row.get("archive_cid") == null) throw new IngressError(409, "LOG_NOT_ARCHIVED", "日志尚未归档");
      try {
        encrypted =
            new String(storage.cat((String) row.get("archive_cid")), StandardCharsets.UTF_8);
      } catch (Exception e) {
        throw new IngressError(503, "ARCHIVE_UNAVAILABLE", "归档暂不可用");
      }
    }
    String plain =
        cipher.decrypt(
            key, (String) row.get("payload_hash"), encrypted, (String) row.get("ciphertext_hash"));
    access(source, key, "RESTORE", "SUCCESS");
    return Map.of(
        "eventKey",
        key,
        "payloadHash",
        row.get("payload_hash"),
        "canonicalJson",
        plain,
        "recoverySource",
        fromArchive ? "IPFS" : "LOCAL_ENCRYPTED_BACKUP");
  }

  void access(LogSettings.Source source, String key, String operation, String outcome) {
    db.update(
        "INSERT INTO audit_evidence_access(id,source_id,event_key,operation,outcome)"
            + " VALUES(?,?,?,?,?)",
        UUID.randomUUID().toString(),
        source.id(),
        key,
        operation,
        outcome);
  }

  static IngressError missing() {
    return new IngressError(404, "LOG_NOT_FOUND", "日志不存在");
  }
}
