package com.bproject.trust.logevidence;

import static org.junit.jupiter.api.Assertions.*;
import com.bproject.trust.ingress.GenericEventInput;
import com.bproject.trust.shared.json.Json;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationLogMappingTest {
  @Test
  void publishedBytesPreserveAuditHashNanosAndInt64() throws Exception {
    byte[] bytes = Files.readAllBytes(Path.of("../examples/audit-operation-log.json"));
    var vectors = Json.MAPPER.readTree(Files.readAllBytes(
        Path.of("../examples/audit-operation-log-vectors.json")));
    var root = Json.MAPPER.readTree(bytes);
    var event = LogInput.MAPPER.treeToValue(root.path("payload").path("event"), LogEvent.class);
    assertEquals(128, event.eventId().length());
    assertEquals(Long.MAX_VALUE, event.durationMs());
    assertEquals(123456789, event.occurredAt().getNano());
    assertEquals(vectors.path("sourceEventId").asText(), event.key());
    assertEquals(vectors.path("payloadHash").asText(), LogInput.hash(LogInput.canonical(event)));
    assertEquals(vectors.path("originalSha256").asText(), Json.sha(bytes));
    assertEquals(vectors.path("originalSizeBytes").asInt(), bytes.length);
    var input = GenericEventInput.parse(bytes);
    assertEquals(event.key(), input.sourceEventId());
    assertEquals(event.key(), input.businessObjectId());
    assertEquals(Instant.parse("2026-01-01T00:00:00.123456Z"), input.occurredAt());
    var inner = (Map<?, ?>) input.payload().get("event");
    assertEquals(new BigDecimal(Long.MAX_VALUE), inner.get("durationMs"));
    assertEquals("2026-01-01T00:00:00.123456789Z", inner.get("occurredAt"));
    var context = vectors.path("canonicalContext");
    var source = new com.bproject.trust.ingress.EvidenceIngressSettings.Source(
        context.path("sourceId").asText(), "0".repeat(64), context.path("orgId").asText(),
        context.path("sourceSystem").asText(), true, java.util.Set.of("read", "submit"), java.util.Set.of("OPERATION_LOG"));
    var canonical = com.bproject.trust.ingress.GenericEventRules.canonical(source, input,
        context.path("id").asText(), context.path("id").asText(), 1);
    byte[] fixed = Files.readAllBytes(Path.of("../examples/audit-operation-log-canonical.json"));
    assertArrayEquals(fixed, canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(vectors.path("eventSha256").asText(), Json.sha(fixed));
  }
}
