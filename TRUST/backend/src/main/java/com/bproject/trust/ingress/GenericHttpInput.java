package com.bproject.trust.ingress;
import java.util.Map;
/** Shared transport validation, without configuration or application startup. */
public final class GenericHttpInput {
  private GenericHttpInput() {}
  private static final com.fasterxml.jackson.databind.ObjectReader RETRY_BODY =
      com.bproject.trust.shared.json.Json.MAPPER
          .copy()
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .readerFor(Object.class);
  public static void mediaType(String type, String encoding) {
    try {
      var media = org.springframework.http.MediaType.parseMediaType(type == null ? "" : type);
      if (!media.getType().equals("application")
          || !media.getSubtype().equals("json")
          || (media.getCharset() != null
              && !media.getCharset().equals(java.nio.charset.StandardCharsets.UTF_8))
          || encoding != null) throw new IllegalArgumentException();
    } catch (Exception e) {
      throw new IngressError(415, "UNSUPPORTED_MEDIA_TYPE", "仅支持未压缩UTF-8 JSON");
    }
  }
  public static void retry(byte[] bytes) {
    if (bytes.length > 64) throw IngressError.invalid();
    try {
      String body =
          java.nio.charset.StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
              .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
              .decode(java.nio.ByteBuffer.wrap(bytes))
              .toString();
      // Preserve empty/JSON-whitespace bodies; otherwise require one complete empty object.
      if (!body.matches("[ \\t\\r\\n]*")) {
        Object value = RETRY_BODY.readValue(body);
        if (!(value instanceof Map<?, ?> object) || !object.isEmpty()) throw IngressError.invalid();
      }
    } catch (Exception e) {
      throw IngressError.invalid();
    }
  }
}
