package com.bproject.trust.ingress;

import static org.junit.jupiter.api.Assertions.*;
import com.bproject.trust.shared.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class GenericSharedContractTest {
  private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

  @Test void diagnosticsUseAuthoritativeParserAndPreservePublicErrors() {
    String valid = GenericEventInputTest.body("{\"n\":1}");
    assertTrue(GenericEventInput.diagnose(bytes(valid)).isEmpty());
    var inputs = Map.of(
        "$.schemaVersion", valid.replace("schemaVersion\":1", "schemaVersion\":1.0"),
        "$.sourceEventId", valid.replace("item-1", ".."),
        "$.eventType", valid.replace("MAINTENANCE_CONFIRMED", "lowercase"),
        "$.businessObjectId", valid.replace("equipment-1", ""),
        "$.occurredAt", valid.replace("02:00:00Z", "02:00:00.0000001Z"),
        "$.payload", GenericEventInputTest.body("{}"));
    inputs.forEach((path, input) -> {
      var error = assertThrows(IngressError.class, () -> GenericEventInput.parse(bytes(input)));
      assertEquals(400, error.status);
      assertEquals(Map.of("code", "INVALID_REQUEST", "message", "通用事件格式不正确"), error.body());
      var diagnostics = GenericEventInput.diagnose(bytes(input));
      assertEquals(path, diagnostics.get(0).path());
      assertFalse(diagnostics.get(0).rule().isBlank());
      assertFalse(diagnostics.get(0).suggestion().isBlank());
    });
    assertEquals("UTF8", GenericEventInput.diagnose(new byte[]{(byte)255}).get(0).rule());
    assertEquals("REQUEST_TOO_LARGE", GenericEventInput.diagnose(new byte[262145]).get(0).rule());
    assertEquals("JSON_SYNTAX", GenericEventInput.diagnose(bytes(valid + "{}")).get(0).rule());
    String privateKey = "private-business-key";
    String diagnostics = Json.write(GenericEventInput.diagnose(bytes(GenericEventInputTest.body(
        "{\"" + privateKey + "\":1e100}"))));
    assertFalse(diagnostics.contains(privateKey));
    assertTrue(diagnostics.contains("member:0"));
  }

  @Test void sharedMediaTypeKeepsExistingAcceptance() {
    for (String type : List.of("application/json", "application/json;charset=UTF-8", "application/json;custom=x"))
      assertDoesNotThrow(() -> GenericHttpInput.mediaType(type, null));
    for (String type : Arrays.asList(null, "", "text/json", "application/json;charset=UTF-16")) {
      var e = assertThrows(IngressError.class, () -> GenericHttpInput.mediaType(type, null));
      assertEquals(415, e.status);
      assertEquals("UNSUPPORTED_MEDIA_TYPE", e.code);
    }
    assertThrows(IngressError.class, () -> GenericHttpInput.mediaType("application/json", "identity"));
  }

  @Test void sharedRetryKeepsWhitespaceAndEmptyObjectSemantics() {
    for (String body : List.of("", " \t\r\n", "{}", " { } "))
      assertDoesNotThrow(() -> GenericHttpInput.retry(bytes(body)));
    for (String body : List.of("[]", "null", "{\"x\":1}", "{}{}", "\uFEFF{}", " ".repeat(65), "\u2003")) {
      var e = assertThrows(IngressError.class, () -> GenericHttpInput.retry(bytes(body)));
      assertEquals(400, e.status);
      assertEquals("INVALID_REQUEST", e.code);
    }
    assertThrows(IngressError.class, () -> GenericHttpInput.retry(new byte[]{(byte)255}));
  }

  @Test void sharedCanonicalPreservesOriginalHashAndEmptyEvidence() {
    var input = GenericEventInputTest.parse(GenericEventInputTest.body("{\"n\":1.0}"));
    var source = new EvidenceIngressSettings.Source("test", "synthetic", "ORG", "SYSTEM", true,
        Set.of("read", "submit", "retry"), Set.of("MAINTENANCE_CONFIRMED"));
    var canonical = Json.map(GenericEventRules.canonical(source, input, "id", "root", 2));
    assertEquals(2, canonical.get("schemaVersion"));
    assertEquals("GENERIC_V1", canonical.get("recordKind"));
    assertEquals("source:test", canonical.get("submittedBy"));
    assertEquals(List.of(), canonical.get("evidence"));
    assertNull(canonical.get("supersedesId"));
    var original = (Map<?, ?>) canonical.get("originalRequest");
    assertEquals(Json.sha(bytes(input.original())), original.get("sha256"));
    assertEquals(bytes(input.original()).length, original.get("sizeBytes"));
  }

  @Test void sharedRetryDecisionRetainsConflictAndNoOpRules() {
    assertTrue(GenericEventRules.retryQueued("FAILED", "FAILED"));
    assertFalse(GenericEventRules.retryQueued("READY", "NEW"));
    assertFalse(GenericEventRules.retryQueued("DONE", "COMMITTED"));
    assertEquals("TASK_RUNNING", assertThrows(IngressError.class,
        () -> GenericEventRules.retryQueued("RUNNING", "COMMITTED")).code);
    assertEquals("INTEGRITY_MISMATCH", assertThrows(IngressError.class,
        () -> GenericEventRules.retryQueued("DONE", "FAILED")).code);
    assertEquals("INTEGRITY_MISMATCH", assertThrows(IngressError.class,
        () -> GenericEventRules.retryQueued("UNKNOWN", "FAILED")).code);
  }
}
