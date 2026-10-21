package com.bproject.trust.logevidence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bproject.trust.ingress.IngressError;
import com.bproject.trust.shared.json.Json;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class LogContractTest {
  @TempDir Path root;

  static LogEvent event(String id, String summary) {
    return new LogEvent(
        1,
        id,
        Instant.parse("2026-01-02T03:04:05.123456789Z"),
        "TRUST",
        "TEST_DOMAIN",
        "TEST_ZONE",
        "test-user",
        "AUTHENTICATED",
        "events",
        "READ",
        "object-1",
        "SUCCESS",
        "GET",
        "/events",
        "trace-1",
        Long.MAX_VALUE,
        "CLIENT",
        summary);
  }

  static byte[] request(LogEvent e) {
    return Json.bytes(
        Map.of(
            "eventKey",
            e.key(),
            "eventId",
            e.eventId(),
            "payloadHash",
            LogInput.hash(LogInput.canonical(e)),
            "event",
            e));
  }

  @Test
  void canonicalPreservesNanosecondsAndInt64() {
    var e = event("e1", "查询测试");
    var input = LogInput.parse(request(e));
    assertEquals(Long.MAX_VALUE, input.event().durationMs());
    assertEquals(123456789, input.event().occurredAt().getNano());
    assertTrue(input.canonical().contains("9223372036854775807"));
    assertTrue(input.canonical().contains("2026-01-02T03:04:05.123456789Z"));
    assertEquals(input.payloadHash(), LogInput.hash(input.canonical()));
    // Captured by executing the supplied AUDIT CanonicalJson and LogEvent sources, not the TRUST
    // mapper.
    assertEquals(
        "b5a4524890c4434c39ec3b323692fba423c200a4dcf7e0ce67c4875e497cd3cd", input.payloadHash());
    assertEquals(
        "74f4ad60d4ea7707028545681538190cc11b7df097daf445e2f7743d556354ac", input.eventKey());
    assertEquals(
        "{\"action\":\"READ\",\"actorTrust\":\"AUTHENTICATED\",\"appCode\":\"TRUST\",\"domainId\":\"TEST_DOMAIN\",\"durationMs\":9223372036854775807,\"eventId\":\"e1\",\"menuId\":\"events\",\"method\":\"GET\",\"objectId\":\"object-1\",\"occurredAt\":\"2026-01-02T03:04:05.123456789Z\",\"outcome\":\"SUCCESS\",\"path\":\"/events\",\"schemaVersion\":1,\"sourceKind\":\"CLIENT\",\"summary\":\"查询测试\",\"traceId\":\"trace-1\",\"userId\":\"test-user\",\"zoneId\":\"TEST_ZONE\"}",
        input.canonical());
  }

  @Test
  void rejectsHashMismatchDuplicateKeysMalformedUtf8AndCoercion() {
    String good = new String(request(event("e1", "")), java.nio.charset.StandardCharsets.UTF_8);
    for (String bad :
        List.of(
            good.replace("9223372036854775807", "9223372036854775808"),
            good.replace("9223372036854775807", "1.5"),
            good.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            good.replace("\"eventId\":\"e1\"", "\"eventId\":\"changed\"")))
      assertEquals(
          400,
          assertThrows(
                  IngressError.class,
                  () -> LogInput.parse(bad.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
              .status);
    assertThrows(IngressError.class, () -> LogInput.parse(new byte[] {(byte) 0xc0, (byte) 0xaf}));
    assertEquals(
        413, assertThrows(IngressError.class, () -> LogInput.parse(new byte[65537])).status);
  }

  @Test
  void authenticatedEncryptionSurvivesRotationAndRejectsTampering() throws Exception {
    Path ring = root.resolve("archive-keyring.json");
    byte[] key = new byte[32];
    new java.security.SecureRandom().nextBytes(key);
    Files.writeString(
        ring,
        Json.write(
            Map.of(
                "keys",
                Map.of(
                    "v1",
                    Base64.getEncoder().encodeToString(key),
                    "v2",
                    Base64.getEncoder().encodeToString(new byte[32])))));
    var settings = mock(LogSettings.class);
    when(settings.read())
        .thenReturn(
            new LogSettings.Config(
                1,
                true,
                "v1",
                ring.toString(),
                Set.of(),
                List.of(),
                new LogSettings.Delivery(false, "", "", Map.of())));
    var cipher = new LogCipher(settings);
    String plain = LogInput.canonical(event("e1", "sensitive evidence")),
        hash = LogInput.hash(plain);
    var encrypted = cipher.encrypt("event-key", hash, plain);
    assertFalse(encrypted.ciphertext().contains("sensitive evidence"));
    assertNotEquals(encrypted.ciphertext(), cipher.encrypt("event-key", hash, plain).ciphertext());
    when(settings.read())
        .thenReturn(
            new LogSettings.Config(
                1,
                true,
                "v2",
                ring.toString(),
                Set.of(),
                List.of(),
                new LogSettings.Delivery(false, "", "", Map.of())));
    assertEquals(
        plain,
        cipher.decrypt("event-key", hash, encrypted.ciphertext(), encrypted.ciphertextHash()));
    assertThrows(
        IngressError.class,
        () -> cipher.decrypt("other", hash, encrypted.ciphertext(), encrypted.ciphertextHash()));
    assertThrows(
        IngressError.class,
        () ->
            cipher.decrypt(
                "event-key", hash, encrypted.ciphertext() + " ", encrypted.ciphertextHash()));
    Files.delete(ring);
    assertThrows(
        IngressError.class,
        () ->
            cipher.decrypt("event-key", hash, encrypted.ciphertext(), encrypted.ciphertextHash()));
  }

  @Test
  void absentConfigurationDisablesAllExternalProcessing() {
    var cfg = new LogSettings(root.toString()).read();
    assertFalse(cfg.enabled());
    assertFalse(cfg.delivery().enabled());
    assertTrue(cfg.managerUserIds().isEmpty());
    assertThrows(
        IngressError.class,
        () ->
            new LogSettings.Source(
                    "x", "", "x", Set.of("read"), Set.of(new LogSettings.Scope("D", "Z", "TRUST")))
                .scope("D", "OTHER", "TRUST"));
  }
}
