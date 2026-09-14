package com.bproject.trust.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class EventRelations {
  private final JdbcTemplate db;

  public EventRelations(JdbcTemplate db) {
    this.db = db;
  }

  public List<String> missing(Map<String, Object> row, String org) {
    var missing = new ArrayList<String>();
    for (String ref :
        db.queryForList(
            "SELECT target FROM event_links WHERE event_id=? AND kind='EVENT'",
            String.class,
            row.get("id")))
      if (db.queryForObject(
              "SELECT COUNT(*) FROM events WHERE org_id=? AND"
                  + " source_system||':'||source_event_id=?",
              Long.class,
              org,
              ref)
          == 0) missing.add(ref);
    return missing;
  }
}
