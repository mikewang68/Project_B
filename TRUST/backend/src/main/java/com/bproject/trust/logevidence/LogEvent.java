package com.bproject.trust.logevidence;

import java.time.Instant;
import java.util.Set;

/** AUDIT v1 wire record. Keep canonical field types aligned with the producer contract. */
public record LogEvent(
    int schemaVersion,
    String eventId,
    Instant occurredAt,
    String appCode,
    String domainId,
    String zoneId,
    String userId,
    String actorTrust,
    String menuId,
    String action,
    String objectId,
    String outcome,
    String method,
    String path,
    String traceId,
    long durationMs,
    String sourceKind,
    String summary) {
  public static final Set<String> ACTIONS =
      Set.of(
          "CREATE",
          "READ",
          "UPDATE",
          "DELETE",
          "LOGIN",
          "LOGOUT",
          "EXPORT",
          "IMPORT",
          "UPLOAD",
          "DOWNLOAD",
          "BACKUP",
          "RESTORE",
          "TRAIN",
          "EXECUTE",
          "NAVIGATE");

  public LogEvent {
    if (schemaVersion != 1 || occurredAt == null || durationMs < 0)
      throw new IllegalArgumentException();
    eventId = identifier(eventId, 128);
    appCode = identifier(appCode, 64);
    domainId = identifier(domainId, 128);
    zoneId = identifier(zoneId, 128);
    userId = bounded(userId, 128);
    menuId = bounded(menuId, 128);
    objectId = bounded(objectId, 192);
    traceId = bounded(traceId, 128);
    method = bounded(method, 16);
    path = bounded(path, 256);
    if (path.contains("?") || path.contains("#")) throw new IllegalArgumentException();
    if (!ACTIONS.contains(action)
        || !Set.of("SUCCESS", "FAILED", "DENIED", "STARTED").contains(outcome)
        || !Set.of("AUTHENTICATED", "ANONYMOUS", "SYSTEM").contains(actorTrust)
        || !Set.of("BUSINESS", "REQUEST", "POLL", "JOB", "CLIENT").contains(sourceKind))
      throw new IllegalArgumentException();
    summary = bounded(summary, 512);
    if ((userId + " " + objectId + " " + path + " " + summary)
        .matches(
            "(?is).*(sk-[a-z0-9]{12,}|bearer\\s+[a-z0-9._-]{12,}|(password|api[_-]?key|token|authorization)\\s*[:=]).*"))
      throw new IllegalArgumentException();
  }

  public String key() {
    return LogInput.hash(domainId + "\n" + zoneId + "\n" + appCode + "\n" + eventId);
  }

  public static String bounded(String value, int max) {
    value = value == null ? "" : value;
    if (value.length() > max || value.chars().anyMatch(c -> c < 32 || c == 127))
      throw new IllegalArgumentException();
    return value;
  }

  static String identifier(String value, int max) {
    value = bounded(value, max);
    if (value.isEmpty() || !value.matches("[A-Za-z0-9_.:-]+")) throw new IllegalArgumentException();
    return value;
  }
}
