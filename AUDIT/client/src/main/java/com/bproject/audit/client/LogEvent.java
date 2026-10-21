package com.bproject.audit.client;

import java.time.Instant;
import java.util.Set;

/** v1 wire contract. Producers derive actor from verified sessions, never browser operator headers. */
public record LogEvent(int schemaVersion, String eventId, Instant occurredAt, String appCode,
                       String domainId, String zoneId, String userId, String actorTrust,
                       String menuId, String action, String objectId, String outcome,
                       String method, String path, String traceId, long durationMs,
                       String sourceKind, String summary) {
    public static final Set<String> ACTIONS = Set.of("CREATE", "READ", "UPDATE", "DELETE", "LOGIN", "LOGOUT",
            "EXPORT", "IMPORT", "UPLOAD", "DOWNLOAD", "BACKUP", "RESTORE", "TRAIN", "EXECUTE", "NAVIGATE");
    public static final Set<String> OUTCOMES = Set.of("SUCCESS", "FAILED", "DENIED", "STARTED");
    public LogEvent {
        if (schemaVersion != 1 || occurredAt == null || durationMs < 0) throw new IllegalArgumentException("Invalid log version/time/duration");
        eventId = identifier(eventId, 128); appCode = identifier(appCode, 64);
        domainId = identifier(domainId, 128); zoneId = identifier(zoneId, 128);
        userId = bounded(userId, 128); menuId = bounded(menuId, 128); objectId = bounded(objectId, 192);
        traceId = bounded(traceId, 128); method = bounded(method, 16); path = bounded(path, 256);
        if (path.contains("?") || path.contains("#")) throw new IllegalArgumentException("Log path must not contain query or fragment");
        if (!ACTIONS.contains(action) || !OUTCOMES.contains(outcome)) throw new IllegalArgumentException("Invalid action/outcome");
        if (!Set.of("AUTHENTICATED", "ANONYMOUS", "SYSTEM").contains(actorTrust)) throw new IllegalArgumentException("Invalid identity assurance");
        if (!Set.of("BUSINESS", "REQUEST", "POLL", "JOB", "CLIENT").contains(sourceKind)) throw new IllegalArgumentException("Invalid source kind");
        summary = bounded(summary, 512);
        String all = userId + " " + objectId + " " + path + " " + summary;
        if (all.matches("(?is).*(sk-[a-z0-9]{12,}|bearer\\s+[a-z0-9._-]{12,}|(password|api[_-]?key|token|authorization)\\s*[:=]).*"))
            throw new IllegalArgumentException("Sensitive value is not allowed in log metadata");
    }
    public String storageKey() { return CanonicalJson.hash(domainId + "\n" + zoneId + "\n" + appCode + "\n" + eventId); }
    private static String identifier(String value, int max) {
        value = bounded(value, max);
        if (value.isEmpty() || !value.matches("[A-Za-z0-9_.:-]+")) throw new IllegalArgumentException("Invalid log identifier");
        return value;
    }
    private static String bounded(String value, int max) {
        value = value == null ? "" : value;
        if (value.length() > max || value.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("Invalid log text");
        return value;
    }
}
