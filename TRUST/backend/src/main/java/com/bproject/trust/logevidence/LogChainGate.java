package com.bproject.trust.logevidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class LogChainGate {
  private final JdbcTemplate db;
  @org.springframework.beans.factory.annotation.Autowired private LogSettings settings;

  public LogChainGate(JdbcTemplate db) {
    this.db = db;
  }

  public boolean allows(String eventId) {
    var rows =
        db.queryForList(
            "SELECT l.*,s.chain_paused FROM audit_evidence_read l CROSS JOIN"
                + " audit_log_settings_read s WHERE l.chain_event_id=? AND s.id=1",
            eventId);
    if (rows.isEmpty()) return true;
    var row = rows.get(0);
    if (Boolean.TRUE.equals(row.get("chain_paused"))) return false;
    if (settings == null) return true;
    try {
      var c = settings.read();
      return c.enabled()
          && c.sources().stream()
              .anyMatch(
                  s ->
                      s.id().equals(row.get("source_id"))
                          && s.genericSourceId().equals(row.get("generic_source_id"))
                          && s.scopes()
                              .contains(
                                  new LogSettings.Scope(
                                      (String) row.get("domain_id"),
                                      (String) row.get("zone_id"),
                                      (String) row.get("app_code"))));
    } catch (Exception unavailable) {
      return false;
    }
  }
}
