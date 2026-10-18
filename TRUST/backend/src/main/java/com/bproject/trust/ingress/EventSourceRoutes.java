package com.bproject.trust.ingress;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Called only inside the event transaction; the same lock also protects WMS relationships. */
public final class EventSourceRoutes {
  private EventSourceRoutes() {}
  public static void reserve(JdbcTemplate db, String org, String source, String kind, String owner) {
    db.execute("LOCK TABLE events IN SHARE ROW EXCLUSIVE MODE");
    var rows=db.queryForList("SELECT record_kind,source_id FROM event_source_routes WHERE org_id=? AND source_system=?",org,source);
    if (rows.isEmpty()) db.update("INSERT INTO event_source_routes(org_id,source_system,record_kind,source_id) VALUES(?,?,?,?)",org,source,kind,owner);
    else if (!kind.equals(rows.get(0).get("record_kind")) || !Objects.equals(owner,rows.get(0).get("source_id")))
      throw new IngressError(409,"SOURCE_NAMESPACE_CONFLICT","来源命名已被其他接入范围保留");
  }
}
