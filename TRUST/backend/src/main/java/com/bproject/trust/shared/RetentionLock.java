package com.bproject.trust.shared;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Lock only the mutable head, then reread in a fresh statement snapshot. Predicates are code
 * constants.
 */
public final class RetentionLock {
  private static final Map<String, String> KEYS =
      Map.ofEntries(
          Map.entry("events", "id"),
          Map.entry("wallets", "id"),
          Map.entry("wallet_requests", "id"),
          Map.entry("fabric_identity", "id"),
          Map.entry("fabric_certificate", "identity_id,version"),
          Map.entry("audit_evidence", "event_key"),
          Map.entry("audit_page_action", "id"),
          Map.entry("audit_delivery", "id"),
          Map.entry("audit_log_settings", "id"),
          Map.entry("tasks", "event_id"));

  private RetentionLock() {}

  public static List<Map<String, Object>> rows(
      JdbcTemplate db, String domain, String where, Object... args) {
    String keys = KEYS.get(domain);
    if (keys == null) throw new IllegalArgumentException("Unknown retention domain");
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("Retention locks require a transaction");
    db.queryForList(
        "SELECT h.retention_revision FROM "
            + domain
            + "_head h JOIN "
            + domain
            + " b USING("
            + keys
            + ") WHERE "
            + where
            + " FOR UPDATE OF h",
        args);
    return db.queryForList("SELECT * FROM " + domain + "_read WHERE " + where, args);
  }

  public static Map<String, Object> row(
      JdbcTemplate db, String domain, String where, Object... args) {
    var rows = rows(db, domain, where, args);
    if (rows.size() != 1)
      throw new org.springframework.dao.IncorrectResultSizeDataAccessException(1, rows.size());
    return rows.get(0);
  }
}
