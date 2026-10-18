package com.bproject.trust.logevidence;

import com.bproject.trust.archiving.AttestationRecord;
import com.bproject.trust.ingress.*;
import com.bproject.trust.ports.LedgerGateway;
import com.bproject.trust.shared.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class LogEvidenceVerification {
  private final LogEvidenceService logs;
  private final GenericEvidenceArchive archive;
  private final LedgerGateway ledger;

  public LogEvidenceVerification(
      LogEvidenceService logs, GenericEvidenceArchive archive, LedgerGateway ledger) {
    this.logs = logs;
    this.archive = archive;
    this.ledger = ledger;
  }

  public Map<String, Object> verify(LogSettings.Source source, String key) {
    source.require("read");
    var log = logs.owned(source, key);
    var receipt = logs.receipt(source, key);
    if (!"COMMITTED".equals(receipt.get("state")))
      throw new IngressError(409, "LOG_NOT_COMMITTED", "日志尚未获得有效上链回执");
    try {
      var event = logs.db.queryForMap("SELECT * FROM events WHERE id=?", log.get("chain_event_id"));
      byte[] originalProjection = archive.verify(event);
      var input = GenericEventInput.parse(originalProjection);
      var expected =
          Map.of(
              "eventKey",
              key,
              "payloadHash",
              log.get("payload_hash"),
              "ciphertextCid",
              log.get("archive_cid"),
              "ciphertextSha256",
              log.get("ciphertext_hash"),
              "keyId",
              log.get("key_id"));
      if (!Json.write(expected).equals(Json.write(input.payload())))
        throw new IllegalArgumentException();
      var record = ledger.find((String) event.get("org_id"), (String) event.get("id"));
      if (record == null) throw new IllegalArgumentException();
      AttestationRecord.assertMatches(AttestationRecord.record(event), record);
      if (!Objects.equals(event.get("tx_id"), record.get("txId")))
        throw new IllegalArgumentException();
      String encrypted =
          new String(logs.storage.cat((String) log.get("archive_cid")), StandardCharsets.UTF_8);
      logs.cipher.decrypt(
          key, (String) log.get("payload_hash"), encrypted, (String) log.get("ciphertext_hash"));
      return Map.of(
          "eventKey",
          key,
          "payloadHash",
          log.get("payload_hash"),
          "transactionId",
          event.get("tx_id"),
          "archiveIntegrity",
          "MATCH",
          "ledgerRegistration",
          "MATCH",
          "businessFactVerified",
          false);
    } catch (Exception e) {
      throw new IngressError(503, "LOG_VERIFICATION_UNCONFIRMED", "归档或账本核验未确认，不能视为通过");
    }
  }
}
