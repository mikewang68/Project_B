package com.bproject.trust.shared.json;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class JsonTest {
  @Test
  void canonicalQuantitiesRoundTripWithoutPrecisionOrNotationChanges() {
    for (String value : List.of("100", "0.000001", "123456789012345.123456")) {
      String first =
          Json.write(Map.of("quantity", new java.math.BigDecimal(value).stripTrailingZeros()));
      assertEquals(first, Json.write(Json.map(first)));
    }
  }
}
