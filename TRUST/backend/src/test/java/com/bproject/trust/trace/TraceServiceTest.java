package com.bproject.trust.trace;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bproject.trust.events.EventRelations;
import com.bproject.trust.shared.web.ApiError;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TraceServiceTest {
  @Test void eventTraceFollowsExplicitEdgesAndNeverExpandsSharedBatch() {
    var db = mock(JdbcTemplate.class);
    var relations = mock(EventRelations.class);
    Map<String,Object> receipt = Map.of("id", "receipt", "source_system", "WMS", "source_event_id", "R",
        "occurred_at", Timestamp.valueOf("2026-09-24 01:00:00"));
    Map<String,Object> ship = Map.of("id", "ship", "source_system", "WMS", "source_event_id", "S",
        "occurred_at", Timestamp.valueOf("2026-09-24 02:00:00"));
    when(db.queryForList(anyString(), eq("ORG"), eq("EVENT"), eq("WMS:S"), eq("EVENT"), eq("WMS:S")))
        .thenReturn(List.of(ship));
    when(db.queryForList(anyString(), eq("ORG"), eq("EVENT"), eq("WMS:R"), eq("EVENT"), eq("WMS:R")))
        .thenReturn(List.of(receipt, ship));
    when(db.queryForList(anyString(), eq("ship"))).thenAnswer(call ->
        call.getArgument(0, String.class).contains("kind='EVENT'")
            ? List.of(Map.of("kind", "EVENT", "target", "WMS:R"))
            : List.of(Map.of("kind", "BATCH", "target", "SHARED"), Map.of("kind", "EVENT", "target", "WMS:R")));
    var trace = new TraceService(db, relations);
    assertEquals(2, ((List<?>) trace.trace("ORG", "EVENT", "WMS:S").get("items")).size());
    verify(db, never()).queryForList(anyString(), eq("ORG"), eq("BATCH"), anyString(), anyString(), anyString());
    assertEquals(400, assertThrows(ApiError.class, () -> trace.trace("ORG", "EVENT", "internal-uuid")).status);
  }
}
