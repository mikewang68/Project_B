package com.bproject.trust.events;

import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
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

  /** Validate immutable WMS snapshots in either arrival order; absent parents remain visible gaps. */
  public void validateSourceRelations(EventInput input, String org) {
    if ("DISPATCH".equals(input.eventType())) {
      for (String ref : input.relatedEventRefs())
        for (var row : db.queryForList(
            "SELECT canonical_json FROM events WHERE org_id=? AND source_system||':'||source_event_id=?",
            org, ref)) validateReceipt(snapshot(row), input);
    } else if ("WAREHOUSE_IN".equals(input.eventType())) {
      for (var row : db.queryForList(
          "SELECT e.canonical_json FROM events e JOIN event_links l ON l.event_id=e.id "
              + "WHERE e.org_id=? AND l.kind='EVENT' AND l.target=? AND e.event_type='DISPATCH'",
          org, input.sourceSystem() + ":" + input.sourceEventId()))
        validateReceipt(input, snapshot(row));
    }
  }

  private EventInput snapshot(Map<String, Object> row) {
    return Json.MAPPER.convertValue(Json.map((String) row.get("canonical_json")).get("event"), EventInput.class);
  }

  static void validateReceipt(EventInput receipt, EventInput dispatch) {
    if (!"WAREHOUSE_IN".equals(receipt.eventType())
        || !receipt.sourceSystem().equals(dispatch.sourceSystem())
        || !receipt.batchId().equals(dispatch.batchId())
        || !java.util.Objects.equals(receipt.unit(), dispatch.unit())
        || receipt.occurredAt().isAfter(dispatch.occurredAt()))
      throw new ApiError(400, "发运来源必须是同来源、同批次、同单位且不晚于发运的收货记录");
    for (String field : List.of("companyCode", "warehouseCode", "ownerCode")) {
      String value = receipt.details().get(field);
      if (value == null || !value.equals(dispatch.details().get(field)))
        throw new ApiError(400, "发运与来源收货的业务范围不一致");
    }
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
