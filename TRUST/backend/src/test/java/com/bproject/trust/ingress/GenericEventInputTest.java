package com.bproject.trust.ingress;

import static org.junit.jupiter.api.Assertions.*;

import com.bproject.trust.shared.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class GenericEventInputTest {
  static String body(String payload) {
    return "{\"schemaVersion\":1,\"sourceEventId\":\"item-1\",\"eventType\":\"MAINTENANCE_CONFIRMED\",\"businessObjectId\":\"equipment-1\",\"occurredAt\":\"2026-10-12T02:00:00Z\",\"payload\":"
        + payload
        + "}";
  }

  static GenericEventInput parse(String value) {
    return GenericEventInput.parse(value.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void preservesOriginalAndNormalizesOnlySemanticEquality() {
    String raw = body("{\"z\":1.0,\"a\":[2,1],\"word\":\"中文\"}");
    var a = parse(raw);
    var b = parse(body("{\"word\":\"中文\",\"a\":[2,1],\"z\":1}"));
    assertEquals(raw, a.original());
    assertEquals(a.request("SOURCE"), b.request("SOURCE"));
    assertNotEquals(a.original(), b.original());
    assertNotEquals(
        a.request("SOURCE"),
        parse(body("{\"z\":1,\"a\":[1,2],\"word\":\"中文\"}")).request("SOURCE"));
    assertNotEquals(
        parse(body("{\"n\":\"1\"}")).request("SOURCE"), parse(body("{\"n\":1}")).request("SOURCE"));
    assertEquals(
        a.request("SOURCE"), parse(raw.replace("02:00:00Z", "10:00:00+08:00")).request("SOURCE"));
  }

  @Test
  void rejectsAmbiguityAndUnsupportedFields() {
    for (String invalid :
        List.of(
            body("{}"),
            body("[]"),
            body("{\"n\":1,\"n\":2}"),
            body("{\"n\":1}").replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            body("{\"n\":1}").replace("\"payload\"", "\"batchId\":null,\"payload\""),
            body("{\"n\":1}").replace("02:00:00Z", "02:00:00.0000001Z"),
            body("{\"n\":1}") + "{}",
            "\uFEFF" + body("{\"n\":1}"),
            body("{\"n\":1e100}"),
            body("{\"n\":\"\\uD800\"}")))
      assertEquals(400, assertThrows(IngressError.class, () -> parse(invalid)).status);
    assertEquals(
        400,
        assertThrows(IngressError.class, () -> GenericEventInput.parse(new byte[] {(byte) 0xff}))
            .status);
  }

  @Test
  void enforcesLimitsBeforePersistence() {
    assertEquals(
        413,
        assertThrows(IngressError.class, () -> GenericEventInput.parse(new byte[262145])).status);
    assertThrows(IngressError.class, () -> parse(body("{\"s\":\"" + "a".repeat(16385) + "\"}")));
    assertThrows(
        IngressError.class,
        () -> parse(body("{\"s\":" + "[".repeat(17) + "1" + "]".repeat(17) + "}")));
    assertThrows(IngressError.class, () -> parse(body("{\"s\":[" + "1,".repeat(2000) + "1]}")));
  }

  @Test
  void rejectsEscapedNulBeforeDatabaseAccess() {
    for (String invalid :
        List.of(
            body("{\"n\":1}").replace("equipment-1", "equipment-\\u0000"),
            body("{\"n\":\"\\u0000\"}"),
            body("{\"\\u0000\":1}"))) {
      var error = assertThrows(IngressError.class, () -> parse(invalid));
      assertEquals(400, error.status);
      assertEquals("INVALID_REQUEST", error.code);
    }
  }

  @Test
  void rejectsOnlyCompleteDotPathSegmentsForSourceEventNumber() {
    for (String number : List.of(".", "..")) {
      var error =
          assertThrows(
              IngressError.class, () -> parse(body("{\"n\":1}").replace("item-1", number)));
      assertEquals(400, error.status);
      assertEquals("INVALID_REQUEST", error.code);
    }
    for (String number : List.of(".item", "item.", "item..1", "..."))
      assertEquals(number, parse(body("{\"n\":1}").replace("item-1", number)).sourceEventId());
  }

  @Test
  void legacyJsonMapperIsNotReconfigured() {
    assertEquals(2, Json.map("{\"x\":1,\"x\":2}").get("x"));
  }

  @Test
  void separatesTaskFailureFromLedgerUncertainty() {
    assertEquals("ACTION_REQUIRED", GenericEventService.status("FAILED", "FAILED", 10, true));
    assertEquals("CONFIRMING", GenericEventService.status("READY", "FAILED", 2, true));
    assertEquals("COMMITTED", GenericEventService.status("DONE", "COMMITTED", 2, false));
    assertEquals("RETRY_WAIT", GenericEventService.status("READY", "FAILED", 2, false));
  }
}
