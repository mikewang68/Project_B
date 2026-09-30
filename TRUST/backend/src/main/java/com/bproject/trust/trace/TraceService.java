package com.bproject.trust.trace;

import com.bproject.trust.events.EventRelations;
import com.bproject.trust.shared.web.ApiError;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TraceService {
  private final JdbcTemplate db;
  private final EventRelations relations;

  public TraceService(JdbcTemplate db, EventRelations relations) {
    this.db = db;
    this.relations = relations;
  }

  public Map<String, Object> trace(String org, String kind, String value) {
    if (!Set.of("BATCH", "BUNDLE", "HANDOVER", "EVENT").contains(kind)
        || value == null || value.isBlank()
        || ("EVENT".equals(kind) && !value.matches("[A-Za-z0-9._-]{1,80}:[A-Za-z0-9._-]{1,120}"))
        || value.length() > 210) throw new ApiError(400, "查询条件不正确");
    LinkedHashMap<String, Map<String, Object>> found = new LinkedHashMap<>();
    var queue = new ArrayDeque<String>();
    var seen = new HashSet<String>();
    queue.add(kind + "\n" + value);
    int visited = 0;
    while (!queue.isEmpty() && found.size() < 500 && visited++ < 1000) {
      String term = queue.remove();
      if (!seen.add(term)) continue;
      String[] parts = term.split("\n", 2);
      String sql =
          "SELECT DISTINCT e.* FROM events e LEFT JOIN event_links l ON l.event_id=e.id WHERE"
              + " e.org_id=? AND ((l.kind=? AND l.target=?) OR (?='EVENT' AND"
              + " e.source_system||':'||e.source_event_id=?)) LIMIT 501";
      for (var row : db.queryForList(sql, org, parts[0], parts[1], parts[0], parts[1])) {
        String id = (String) row.get("id");
        if (found.containsKey(id)) continue;
        if (found.size() >= 500) break;
        found.put(id, row);
        queue.add("EVENT\n" + row.get("source_system") + ":" + row.get("source_event_id"));
        for (var version : db.queryForList(
            "SELECT source_system,source_event_id FROM events WHERE root_id=? AND org_id=?",
            row.get("root_id"), org))
          queue.add("EVENT\n" + version.get("source_system") + ":" + version.get("source_event_id"));
        // Batch, bundle and handover are search indexes, not evidence of parentage.
        // After finding the starting records, follow only explicit event relationships.
        for (var l : db.queryForList("SELECT kind,target FROM event_links WHERE event_id=? AND kind='EVENT'", id))
          queue.add(l.get("kind") + "\n" + l.get("target"));
      }
    }
    var items = new ArrayList<>(found.values());
    items.sort(Comparator.comparing(r -> r.get("occurred_at").toString()));
    var gaps = new TreeSet<String>();
    for (var row : items) gaps.addAll(relations.missing(row, org));
    return Map.of(
        "items",
        items,
        "missingReferences",
        gaps,
        "truncated",
        !queue.isEmpty(),
        "queryKind",
        kind,
        "queryValue",
        value);
  }
}
