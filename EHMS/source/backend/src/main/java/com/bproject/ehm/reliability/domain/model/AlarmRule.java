package com.bproject.ehm.reliability.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;
import java.util.Locale;

public record AlarmRule(
        String ruleCode, String name, String assetType, String metric,
        String conditionExpression, String recoveryExpression, String severity,
        int persistenceSeconds, String versionLabel, String status,
        long hitCount, long falsePositiveCount, String owner,
        Instant createdAt, Instant updatedAt, Long version
) {
    public static AlarmRule create(String code, String name, String assetType, String metric,
                                   String condition, String recovery, String severity,
                                   int persistenceSeconds, String owner, Instant now) {
        if (persistenceSeconds < 0 || persistenceSeconds > 86400) {
            throw new IllegalArgumentException("持续时间必须为0～86400秒");
        }
        return new AlarmRule(required(code, "规则编码").toUpperCase(Locale.ROOT), required(name, "规则名称"),
                required(assetType, "设备类型"), required(metric, "监测指标"), required(condition, "触发条件"),
                fallback(recovery, "连续正常3个窗口后恢复"), normalizeSeverity(severity), persistenceSeconds,
                "v1.0", "DRAFT", 0, 0, fallback(owner, "设备工程师"), now, now, null);
    }

    public AlarmRule publish(String operator, Instant now) {
        if ("PUBLISHED".equals(status)) throw new DomainConflictException("规则已经发布");
        if ("DISABLED".equals(status)) throw new DomainConflictException("已停用规则需先修订形成新版本，不能直接恢复");
        return copy("PUBLISHED", versionLabel, now);
    }

    public AlarmRule disable(String reason, Instant now) {
        if (!"PUBLISHED".equals(status)) throw new DomainConflictException("只有已发布规则可以停用");
        return copy("DISABLED", versionLabel + "-disabled", now);
    }

    private AlarmRule copy(String targetStatus, String targetVersion, Instant now) {
        return new AlarmRule(ruleCode, name, assetType, metric, conditionExpression,
                recoveryExpression, severity, persistenceSeconds, targetVersion, targetStatus,
                hitCount, falsePositiveCount, owner, createdAt, now, version);
    }

    private static String normalizeSeverity(String value) {
        String normalized = fallback(value, "L2").toUpperCase(Locale.ROOT);
        if (!normalized.matches("L[1-4]|DATA")) throw new IllegalArgumentException("告警等级仅支持L1～L4或DATA");
        return normalized;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private static String fallback(String value, String replacement) {
        return value == null || value.isBlank() ? replacement : value.trim();
    }
}
