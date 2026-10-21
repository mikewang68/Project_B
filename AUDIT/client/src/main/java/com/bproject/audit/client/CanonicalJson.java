package com.bproject.audit.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;

public final class CanonicalJson {
    private CanonicalJson() {}
    public static String encode(ObjectMapper mapper, LogEvent event) {
        try {
            var map = mapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).convertValue(event, java.util.Map.class);
            return mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(new TreeMap<>(map));
        } catch (Exception e) { throw new IllegalArgumentException("Cannot serialize log event", e); }
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
