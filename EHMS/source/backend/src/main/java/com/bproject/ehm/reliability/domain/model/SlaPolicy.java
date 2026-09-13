package com.bproject.ehm.reliability.domain.model;

import java.time.Instant;
import java.util.Locale;

public record SlaPolicy(
        String severity, int acknowledgeMinutes, int assignMinutes, int recoverMinutes,
        String escalationRole, boolean enabled, Instant updatedAt, Long version
) {
    public static SlaPolicy create(String severity, int acknowledgeMinutes, int assignMinutes,
                                   int recoverMinutes, String escalationRole, Instant now) {
        String level = severity == null ? "" : severity.trim().toUpperCase(Locale.ROOT);
        if (!level.matches("L[1-4]|DATA")) throw new IllegalArgumentException("SLA等级仅支持L1～L4或DATA");
        if (acknowledgeMinutes < 1 || assignMinutes < acknowledgeMinutes || recoverMinutes < assignMinutes) {
            throw new IllegalArgumentException("SLA时限必须满足：确认≥1分钟，分派≥确认，恢复≥分派");
        }
        String role = escalationRole == null || escalationRole.isBlank() ? "设备主管" : escalationRole.trim();
        return new SlaPolicy(level, acknowledgeMinutes, assignMinutes, recoverMinutes, role, true, now, null);
    }
}
