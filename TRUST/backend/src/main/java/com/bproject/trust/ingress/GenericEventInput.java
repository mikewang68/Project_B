package com.bproject.trust.ingress;

import com.bproject.trust.shared.json.Json;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/** The legacy mapper/DTO must not change when this protocol changes. */
public record GenericEventInput(
    String sourceEventId,
    String eventType,
    String businessObjectId,
    Instant occurredAt,
    Map<String, Object> payload,
    String supersedesId,
    String original) {
  public static final int LIMIT = 262144;
  private static final ObjectMapper READER =
      Json.MAPPER
          .copy()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  /** Diagnostics never include input values or parser exception messages. */
  public record Diagnostic(String path, String rule, String suggestion) {}

  private static final class InputFailure extends IngressError {
    final Diagnostic diagnostic;
    InputFailure(String path, String rule, String suggestion) {
      super(400, "INVALID_REQUEST", "通用事件格式不正确");
      diagnostic = new Diagnostic(path, rule, suggestion);
    }
  }

  private static InputFailure bad(String path, String rule, String suggestion) {
    return new InputFailure(path, rule, suggestion);
  }

  public static List<Diagnostic> diagnose(byte[] bytes) {
    try {
      parse(bytes);
      return List.of();
    } catch (InputFailure e) {
      return List.of(e.diagnostic);
    } catch (IngressError e) {
      return List.of(new Diagnostic("$", e.code,
          e.status == 413 ? "Keep the UTF-8 request at or below 262144 bytes." : "Provide one valid JSON object."));
    }
  }

  public static GenericEventInput parse(byte[] bytes) {
    if (bytes.length > LIMIT) throw new IngressError(413, "REQUEST_TOO_LARGE", "事件内容过大");
    try {
      String text;
      try {
        text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString();
      } catch (Exception e) {
        throw bad("$", "UTF8", "Encode the request as valid UTF-8.");
      }
      if (text.startsWith("\uFEFF")) throw bad("$", "BOM", "Remove the UTF-8 byte order mark.");
      Object root;
      try {
        root = READER.readValue(text, Object.class);
      } catch (Exception e) {
        throw bad("$", "JSON_SYNTAX", "Use one complete JSON value with unique object keys.");
      }
      if (!(root instanceof Map<?, ?> raw)) throw bad("$", "OBJECT", "Provide a JSON object.");
      var fields = Set.of("schemaVersion", "sourceEventId", "eventType", "businessObjectId",
          "occurredAt", "payload", "supersedesId");
      if (!fields.containsAll(raw.keySet()))
        throw bad("$", "UNKNOWN_FIELD", "Use only the documented top-level fields; place business data inside payload.");
      if (!(raw.get("schemaVersion") instanceof Integer v) || v != 1)
        throw bad("$.schemaVersion", "SCHEMA_VERSION", "Use the integer 1.");
      String eventId = string(raw.get("sourceEventId"), 120, "$.sourceEventId");
      String type = string(raw.get("eventType"), 40, "$.eventType");
      if (!eventId.matches("[A-Za-z0-9._-]{1,120}") || Set.of(".", "..").contains(eventId))
        throw bad("$.sourceEventId", "EVENT_ID", "Use 1-120 letters, digits, dots, underscores or hyphens; do not use a standalone dot segment.");
      if (!type.matches("[A-Z_]{1,40}"))
        throw bad("$.eventType", "EVENT_TYPE", "Use 1-40 uppercase letters or underscores.");
      String object = string(raw.get("businessObjectId"), 120, "$.businessObjectId");
      String time = string(raw.get("occurredAt"), 80, "$.occurredAt");
      Instant at;
      try {
        at = OffsetDateTime.parse(time).toInstant();
      } catch (Exception e) {
        throw bad("$.occurredAt", "TIMESTAMP", "Use an ISO-8601 timestamp with an explicit UTC offset.");
      }
      if (at.getNano() % 1000 != 0)
        throw bad("$.occurredAt", "TIME_PRECISION", "Use microsecond precision or less.");
      if (!(raw.get("payload") instanceof Map<?, ?> p) || p.isEmpty())
        throw bad("$.payload", "NONEMPTY_OBJECT", "Provide a non-empty JSON object.");
      @SuppressWarnings("unchecked")
      var payload = (Map<String, Object>) normalize(p, 0, new int[] {0}, "$.payload");
      String previous = raw.get("supersedesId") == null ? null
          : string(raw.get("supersedesId"), 36, "$.supersedesId");
      if (previous != null) {
        try {
          if (!UUID.fromString(previous).toString().equals(previous)) throw new IllegalArgumentException();
        } catch (Exception e) {
          throw bad("$.supersedesId", "UUID", "Use a canonical lowercase UUID or null.");
        }
      }
      return new GenericEventInput(eventId, type, object, at, payload, previous, text);
    } catch (IngressError e) {
      throw e;
    } catch (Exception e) {
      throw IngressError.invalid();
    }
  }

  private static String string(Object o, int max, String path) {
    if (!(o instanceof String s) || s.isBlank() || s.length() > max)
      throw bad(path, "STRING", "Provide a non-blank string of at most " + max + " UTF-16 units.");
    validateUnicode(s, path);
    return s;
  }

  private static void validateUnicode(String s, String path) {
    if (s.indexOf('\0') >= 0) throw bad(path, "UNICODE", "Remove NUL and unpaired surrogate characters.");
    try {
      StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
          .encode(java.nio.CharBuffer.wrap(s));
    } catch (Exception e) {
      throw bad(path, "UNICODE", "Remove NUL and unpaired surrogate characters.");
    }
  }

  private static Object normalize(Object o, int depth, int[] count, String path) {
    if (depth > 16 || ++count[0] > 2000)
      throw bad(path, "STRUCTURE_LIMIT", "Limit payload depth to 16 and total value nodes to 2000.");
    if (o instanceof Map<?, ?> m) {
      var result = new TreeMap<String, Object>();
      int index = 0;
      for (var entry : m.entrySet()) {
        // Object keys can be business data, so diagnostics identify ordinal members instead.
        String member = path + ".<member:" + index++ + ">";
        String key = string(entry.getKey(), 120, member + ".<key>");
        result.put(key, normalize(entry.getValue(), depth + 1, count, member));
      }
      return result;
    }
    if (o instanceof List<?> values) {
      var result = new ArrayList<Object>();
      for (int i = 0; i < values.size(); i++)
        result.add(normalize(values.get(i), depth + 1, count, path + "[" + i + "]"));
      return result;
    }
    if (o instanceof String s) {
      if (s.length() > 16384) throw bad(path, "STRING_SIZE", "Limit strings to 16384 UTF-16 units.");
      validateUnicode(s, path);
      return s;
    }
    if (o instanceof Number n) {
      var value = new BigDecimal(n.toString()).stripTrailingZeros();
      if (value.precision() > 38 || Math.abs((long) value.scale()) > 18)
        throw bad(path, "NUMBER_RANGE", "Use normalized decimal precision at most 38 and absolute scale at most 18.");
      return value;
    }
    if (o == null || o instanceof Boolean) return o;
    throw bad(path, "JSON_VALUE", "Use a JSON value.");
  }

  public Map<String, Object> event(String source) {
    var e = new TreeMap<String, Object>();
    e.put("schemaVersion", 1);
    e.put("sourceSystem", source);
    e.put("sourceEventId", sourceEventId);
    e.put("eventType", eventType);
    e.put("businessObjectId", businessObjectId);
    e.put("occurredAt", occurredAt);
    e.put("payload", payload);
    return e;
  }

  public String request(String source) {
    var m = new TreeMap<String, Object>();
    m.put("event", event(source));
    m.put("supersedesId", supersedesId);
    return Json.write(m);
  }
}
