package com.bproject.trust.integrations.wms;

import static org.junit.jupiter.api.Assertions.*;

import com.bproject.trust.events.EventInput;
import com.bproject.trust.identity.IntegrationSettings;
import com.bproject.trust.shared.web.ApiError;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WmsIngressControllerTest {
  final IntegrationSettings.Service service =
      new IntegrationSettings.Service("wms", "unused", "ORG", "WMS", "C", "W", "O");

  EventInput input(String type, List<String> refs, Map<String, String> details, String batch, BigDecimal qty) {
    return new EventInput("WMS", "SHIP-60", type, "ORDER", batch,
        Instant.parse("2026-09-24T10:00:00Z"), List.of(), null, List.of(), refs,
        qty, "吨", null, null, null, List.of(), details);
  }

  Map<String, String> details() {
    return new HashMap<>(Map.of("companyCode", "C", "warehouseCode", "W", "ownerCode", "O",
        "operatorId", "stable-user-1", "allocationId", "allocation-1"));
  }

  @Test void acceptsDispatchWithExplicitReceiptAndPositiveQuantity() {
    assertDoesNotThrow(() -> WmsIngressController.validate(service,
        input("DISPATCH", List.of("WMS:RECEIPT-100"), details(), "BATCH-1", new BigDecimal("60"))));
  }

  @Test void rejectsMissingSelfCrossSourceAndReceiptParentage() {
    for (List<String> refs : List.of(List.<String>of(), List.of("WMS:SHIP-60"), List.of("OTHER:RECEIPT")))
      assertEquals(400, assertThrows(ApiError.class, () -> WmsIngressController.validate(service,
          input("DISPATCH", refs, details(), "BATCH-1", BigDecimal.ONE))).status);
    assertThrows(ApiError.class, () -> WmsIngressController.validate(service,
        input("WAREHOUSE_IN", List.of("WMS:OTHER"), details(), "BATCH-1", BigDecimal.ONE)));
  }

  @Test void rejectsNullOperatorEmptyBatchZeroQuantityAndWrongScopeWithoutServerError() {
    var missingOperator = details();
    missingOperator.put("operatorId", null);
    assertEquals(400, assertThrows(ApiError.class, () -> WmsIngressController.validate(service,
        input("WAREHOUSE_IN", List.of(), missingOperator, "BATCH-1", BigDecimal.ONE))).status);
    for (String batch : new String[] {null, "", " ", "-"})
      assertThrows(ApiError.class, () -> WmsIngressController.validate(service,
          input("WAREHOUSE_IN", List.of(), details(), batch, BigDecimal.ONE)));
    assertThrows(ApiError.class, () -> WmsIngressController.validate(service,
        input("WAREHOUSE_IN", List.of(), details(), "BATCH-1", BigDecimal.ZERO)));
    var wrong = details(); wrong.put("ownerCode", "OTHER");
    assertEquals(403, assertThrows(ApiError.class, () -> WmsIngressController.validate(service,
        input("WAREHOUSE_IN", List.of(), wrong, "BATCH-1", BigDecimal.ONE))).status);
  }
}
