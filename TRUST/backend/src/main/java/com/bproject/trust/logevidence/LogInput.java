package com.bproject.trust.logevidence;

import com.bproject.trust.ingress.IngressError;
import com.bproject.trust.shared.json.Json;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

public record LogInput(
    String eventKey, String eventId, String payloadHash, LogEvent event, String canonical) {
  public static final int LIMIT = 65536;
  public static final ObjectMapper MAPPER =
      Json.MAPPER
          .copy()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

  public static LogInput parse(byte[] bytes) {
    if (bytes.length > LIMIT) throw new IngressError(413, "LOG_TOO_LARGE", "日志请求超过大小限制");
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      JsonNode root = MAPPER.readTree(text);
      if (!root.isObject()
          || root.size() != 4
          || !root.has("eventKey")
          || !root.has("eventId")
          || !root.has("payloadHash")
          || !root.has("event")) throw new IllegalArgumentException();
      var wire = root.get("event");
      if (!wire.isObject()
          || !wire.path("schemaVersion").isIntegralNumber()
          || wire.path("schemaVersion").intValue() != 1
          || !wire.path("durationMs").isIntegralNumber()
          || !wire.path("durationMs").canConvertToLong()
          || !wire.path("occurredAt").isTextual()) throw new IllegalArgumentException();
      for (var field :
          List.of(
              "eventId",
              "appCode",
              "domainId",
              "zoneId",
              "userId",
              "actorTrust",
              "menuId",
              "action",
              "objectId",
              "outcome",
              "method",
              "path",
              "traceId",
              "sourceKind",
              "summary"))
        if (wire.hasNonNull(field) && !wire.get(field).isTextual())
          throw new IllegalArgumentException();
      LogEvent event = MAPPER.treeToValue(root.get("event"), LogEvent.class);
      String key = text(root, "eventKey"),
          id = text(root, "eventId"),
          hash = text(root, "payloadHash");
      String canonical = canonical(event);
      if (!key.equals(event.key()) || !id.equals(event.eventId()) || !hash.equals(hash(canonical)))
        throw new IllegalArgumentException();
      return new LogInput(key, id, hash, event, canonical);
    } catch (Exception e) {
      throw new IngressError(400, "INVALID_LOG", "日志字段、事件键或摘要不匹配");
    }
  }

  private static String text(JsonNode n, String key) {
    if (!n.path(key).isTextual()) throw new IllegalArgumentException();
    return n.get(key).textValue();
  }

  public static String canonical(LogEvent event) {
    try {
      var map = MAPPER.convertValue(event, Map.class);
      return MAPPER
          .writer()
          .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .writeValueAsString(new TreeMap<>(map));
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid audit event", e);
    }
  }

  public static String hash(String text) {
    return Json.sha(text.getBytes(StandardCharsets.UTF_8));
  }
}
