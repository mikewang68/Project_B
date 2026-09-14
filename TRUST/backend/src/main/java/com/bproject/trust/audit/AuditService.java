package com.bproject.trust.audit;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final JdbcTemplate db;

  public AuditService(JdbcTemplate db) {
    this.db = db;
  }

  public void record(String org, String actor, String action, String id, String detail) {
    db.update(
        "INSERT INTO audit_log(id,org_id,actor,action,object_id,detail) VALUES(?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        org,
        actor,
        action,
        id,
        detail);
  }

  public List<Map<String, Object>> list(String org) {
    return db.queryForList(
        "SELECT * FROM audit_log WHERE org_id=? ORDER BY happened_at DESC LIMIT 200", org);
  }
}
