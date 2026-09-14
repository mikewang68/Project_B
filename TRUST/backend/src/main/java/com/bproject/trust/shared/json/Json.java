package com.bproject.trust.shared.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

public final class Json {
  private Json() {}

  public static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("无法序列化记录", e);
    }
  }

  public static Map<String, Object> map(String text) {
    try {
      return MAPPER.readValue(text, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalArgumentException("记录格式错误", e);
    }
  }

  public static byte[] bytes(Object value) {
    return write(value).getBytes(StandardCharsets.UTF_8);
  }

  public static String sha(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
