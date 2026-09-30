package com.bdemo.iam.log;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** No sink is registered by default: no SYS database, account, token or endpoint is touched. */
@Component
public class SysAuditExport {
  private final JdbcTemplate db;
  private final ObjectProvider<SysAuditSink> sink;

  public SysAuditExport(JdbcTemplate db, ObjectProvider<SysAuditSink> sink) {
    this.db = db;
    this.sink = sink;
  }

  @Scheduled(fixedDelayString = "${iam.audit.export-ms:30000}")
  public void export() {
    var target = sink.getIfAvailable();
    if (target == null) return;
    for (var event :
        db.queryForList(
            "SELECT a.* FROM iam.iam_audit a LEFT JOIN iam.sys_audit_receipt r ON a.id=r.audit_id"
                + " WHERE r.audit_id IS NULL ORDER BY a.created_at,a.id LIMIT 50")) {
      String id = (String) event.get("id");
      try {
        if (!id.equals(target.append(id, event))) return;
        db.update(
            "INSERT INTO iam.sys_audit_receipt(audit_id) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM"
                + " iam.sys_audit_receipt WHERE audit_id=?)",
            id,
            id);
      } catch (Exception e) {
        return;
      } // Durable source row remains unacknowledged and will retry.
    }
  }
}
