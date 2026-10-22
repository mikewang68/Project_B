package com.bproject.trust.ingress;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;

/** Called only inside the event transaction; the same lock also protects WMS relationships. */
public final class EventSourceRoutes {
  private EventSourceRoutes() {}

  /** The coordination row is committed before the business transaction takes its lock. */
  public static void prepare(JdbcTemplate db, String org, String source) {
    var tx =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                db.getDataSource()));
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    try {
      tx.executeWithoutResult(
          s -> {
            if (db.queryForObject(
                    "SELECT COUNT(*) FROM source_mutex WHERE org_id=? AND source_system=?",
                    Long.class,
                    org,
                    source)
                == 0)
              db.update("INSERT INTO source_mutex(org_id,source_system) VALUES(?,?)", org, source);
          });
    } catch (org.springframework.dao.DuplicateKeyException concurrentFirstUse) {
      // Another caller committed this same immutable coordination key. Our transaction rolled back.
    }
  }

  public static void reserve(
      JdbcTemplate db, String org, String source, String kind, String owner) {
    if (db.queryForList(
                "SELECT org_id FROM source_mutex WHERE org_id=? AND source_system=? FOR UPDATE",
                org,
                source)
            .size()
        != 1) throw new IllegalStateException("来源协调记录未准备");
    var rows =
        db.queryForList(
            "SELECT record_kind,source_id FROM event_source_routes WHERE org_id=? AND"
                + " source_system=?",
            org,
            source);
    if (rows.isEmpty())
      db.update(
          "INSERT INTO event_source_routes(org_id,source_system,record_kind,source_id)"
              + " VALUES(?,?,?,?)",
          org,
          source,
          kind,
          owner);
    else if (!kind.equals(rows.get(0).get("record_kind"))
        || !Objects.equals(owner, rows.get(0).get("source_id")))
      throw new IngressError(409, "SOURCE_NAMESPACE_CONFLICT", "来源命名已被其他接入范围保留");
  }
}
