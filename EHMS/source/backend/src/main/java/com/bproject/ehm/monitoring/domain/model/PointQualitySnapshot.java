package com.bproject.ehm.monitoring.domain.model;

import java.time.Duration;
import java.time.Instant;

public record PointQualitySnapshot(
        String pointCode,
        String assetCode,
        QualityStatus status,
        Double value,
        Instant sourceTimestamp,
        Instant receivedAt,
        Instant assessedAt,
        String message,
        int consecutiveFailures,
        Long version
) {
    public static PointQualitySnapshot assess(String pointCode, String assetCode, boolean enabled,
                                              int sampleIntervalSeconds, Double lowerLimit, Double upperLimit,
                                              Double value, Instant sourceTimestamp, Instant receivedAt,
                                              PointQualitySnapshot previous, Instant now) {
        QualityDecision decision = decide(enabled, sampleIntervalSeconds, lowerLimit, upperLimit,
                value, sourceTimestamp, receivedAt, now);
        int failures = decision.status().usable() ? 0 : (previous == null ? 1 : previous.consecutiveFailures() + 1);
        return new PointQualitySnapshot(pointCode, assetCode, decision.status(), value, sourceTimestamp,
                receivedAt, now, decision.message(), failures, previous == null ? null : previous.version());
    }

    public static PointQualitySnapshot missing(String pointCode, String assetCode, boolean enabled, Instant now) {
        return new PointQualitySnapshot(pointCode, assetCode, enabled ? QualityStatus.MISSING : QualityStatus.DISABLED,
                null, null, null, now, enabled ? "尚未接收到测点数据" : "测点已停用", enabled ? 1 : 0, null);
    }

    private static QualityDecision decide(boolean enabled, int interval, Double lower, Double upper, Double value,
                                          Instant sourceTimestamp, Instant receivedAt, Instant now) {
        if (!enabled) return new QualityDecision(QualityStatus.DISABLED, "测点已停用，不参与健康评估");
        if (value == null) return new QualityDecision(QualityStatus.INVALID, "采样值为空");
        if (sourceTimestamp == null || receivedAt == null) {
            return new QualityDecision(QualityStatus.INVALID, "缺少源时间或接收时间");
        }
        if (sourceTimestamp.isAfter(receivedAt.plusSeconds(Math.max(2, interval)))) {
            return new QualityDecision(QualityStatus.INVALID, "源时间晚于接收时间，需检查时钟同步");
        }
        long ageSeconds = Math.max(0, Duration.between(sourceTimestamp, now).toSeconds());
        if (ageSeconds > Math.max(30, interval * 5L)) {
            return new QualityDecision(QualityStatus.MISSING, "数据已超过允许新鲜度窗口");
        }
        long transportSeconds = Math.max(0, Duration.between(sourceTimestamp, receivedAt).toSeconds());
        if (transportSeconds > Math.max(2, interval * 3L)) {
            return new QualityDecision(QualityStatus.DELAYED, "采集到接收的链路延迟超过阈值");
        }
        if ((lower != null && value < lower) || (upper != null && value > upper)) {
            return new QualityDecision(QualityStatus.OUT_OF_RANGE, "采样值超出测点配置量程");
        }
        return new QualityDecision(QualityStatus.GOOD, "时间、量程和完整性检查通过");
    }

    private record QualityDecision(QualityStatus status, String message) {
    }
}
