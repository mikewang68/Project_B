package com.bproject.trust.selfcheck;

import com.bproject.trust.ingress.EvidenceIngressSettings.Source;
import com.bproject.trust.shared.json.Json;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.nio.file.*;
import java.util.*;

/** Explicit synthetic input only; never discovers application configuration. */
public final class Profile {
  private Profile() {}
  public static Source read(Path path) throws Exception {
    if (Files.size(path) > 16384) throw new IllegalArgumentException("Profile exceeds 16384 bytes");
    var mapper = Json.MAPPER.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    var node = mapper.readTree(Files.readAllBytes(path));
    Set<String> fields = Set.of("sourceId", "orgId", "sourceSystem", "operations", "allowedEventTypes");
    if (!node.isObject() || node.size() != fields.size()) throw new IllegalArgumentException("Invalid synthetic profile fields");
    var names = node.fieldNames();
    while (names.hasNext()) if (!fields.contains(names.next())) throw new IllegalArgumentException("Unknown profile field");
    String id = identifier(node.get("sourceId"),70), org = identifier(node.get("orgId"),80), system = identifier(node.get("sourceSystem"),80);
    Set<String> operations = strings(node.get("operations"),3), types = strings(node.get("allowedEventTypes"),80);
    if (!operations.contains("read") || !Set.of("read","submit","retry").containsAll(operations)
        || types.stream().anyMatch(t -> !t.matches("[A-Z_]{1,40}") || Set.of("WAREHOUSE_IN","DISPATCH").contains(t)))
      throw new IllegalArgumentException("Invalid synthetic operations or event types");
    return new Source(id, "synthetic-not-a-credential", org, system, true, operations, types);
  }
  private static String identifier(com.fasterxml.jackson.databind.JsonNode value,int max) {
    if (value == null || !value.isTextual() || !value.textValue().matches("[A-Za-z0-9._-]{1,"+max+"}"))
      throw new IllegalArgumentException("Invalid synthetic identifier");
    return value.textValue();
  }
  private static Set<String> strings(com.fasterxml.jackson.databind.JsonNode values,int max) {
    if (values == null || !values.isArray() || values.isEmpty() || values.size()>max) throw new IllegalArgumentException("Invalid profile list");
    Set<String> result = new HashSet<>();
    for (var value : values) if (!value.isTextual() || !result.add(value.textValue())) throw new IllegalArgumentException("Invalid profile list item");
    return Set.copyOf(result);
  }
}
