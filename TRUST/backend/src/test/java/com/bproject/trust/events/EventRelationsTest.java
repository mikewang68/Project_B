package com.bproject.trust.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class EventRelationsTest {
  EventInput input(String id, String type, String batch, String unit, String owner, String time) {
    return new EventInput("WMS", id, type, "ORDER", batch, Instant.parse(time),
        List.of(), null, List.of(), type.equals("DISPATCH") ? List.of("WMS:RECEIPT") : List.of(),
        new BigDecimal(type.equals("DISPATCH") ? "60" : "100"), unit, null, null, null,
        List.of(), Map.of("companyCode", "C", "warehouseCode", "W", "ownerCode", owner));
  }
  EventInput receipt() { return input("RECEIPT", "WAREHOUSE_IN", "BATCH", "吨", "O", "2026-09-24T01:00:00Z"); }
  EventInput dispatch() { return input("SHIP", "DISPATCH", "BATCH", "吨", "O", "2026-09-24T02:00:00Z"); }
  Map<String,Object> row(EventInput input) { return Map.of("canonical_json", Json.write(Map.of("event", input))); }

  @Test void validatesImmutableParentFacts() {
    assertDoesNotThrow(() -> EventRelations.validateReceipt(receipt(), dispatch()));
    for (EventInput invalid : List.of(
        input("P", "DISPATCH", "BATCH", "吨", "O", "2026-09-24T01:00:00Z"),
        input("P", "WAREHOUSE_IN", "OTHER", "吨", "O", "2026-09-24T01:00:00Z"),
        input("P", "WAREHOUSE_IN", "BATCH", "千克", "O", "2026-09-24T01:00:00Z"),
        input("P", "WAREHOUSE_IN", "BATCH", "吨", "OTHER", "2026-09-24T01:00:00Z"),
        input("P", "WAREHOUSE_IN", "BATCH", "吨", "O", "2026-09-24T03:00:00Z")))
      assertThrows(ApiError.class, () -> EventRelations.validateReceipt(invalid, dispatch()));
  }

  @Test void acceptsMissingParentAndValidatesLateArrivalAgainstStoredChild() {
    var db = mock(JdbcTemplate.class);
    var relations = new EventRelations(db);
    when(db.queryForList(anyString(), eq("ORG"), anyString())).thenReturn(List.of());
    assertDoesNotThrow(() -> relations.validateSourceRelations(dispatch(), "ORG"));
    when(db.queryForList(anyString(), eq("ORG"), anyString())).thenReturn(List.of(row(dispatch())));
    assertDoesNotThrow(() -> relations.validateSourceRelations(receipt(), "ORG"));
    var wrong = input("RECEIPT", "WAREHOUSE_IN", "WRONG", "吨", "O", "2026-09-24T01:00:00Z");
    assertThrows(ApiError.class, () -> relations.validateSourceRelations(wrong, "ORG"));
  }
}
