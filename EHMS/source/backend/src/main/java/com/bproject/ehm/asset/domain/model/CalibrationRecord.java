package com.bproject.ehm.asset.domain.model;

import java.time.Instant;
import java.util.Locale;

public record CalibrationRecord(
        String calibrationNo,
        String assetCode,
        String componentCode,
        String pointCode,
        String sensorCode,
        String calibrationType,
        Double beforeValue,
        Double afterValue,
        Double tolerance,
        String unit,
        String result,
        String certificateNo,
        String organization,
        String operator,
        Instant calibratedAt,
        Instant validUntil,
        String attachmentRef,
        String remark,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static CalibrationRecord create(String no, String assetCode, String componentCode,
                                           String pointCode, String sensorCode, String calibrationType,
                                           Double beforeValue, Double afterValue, Double tolerance,
                                           String unit, String result, String certificateNo,
                                           String organization, String operator, Instant calibratedAt,
                                           Instant validUntil, String attachmentRef, String remark, Instant now) {
        Instant actualAt = calibratedAt == null ? now : calibratedAt;
        if (validUntil != null && !validUntil.isAfter(actualAt)) {
            throw new IllegalArgumentException("校准有效期必须晚于校准时间");
        }
        return new CalibrationRecord(normalize(no), normalize(assetCode), trim(componentCode),
                trim(pointCode), required(sensorCode, "传感器编码"), fallback(calibrationType, "周期校准"),
                beforeValue, afterValue, tolerance, trim(unit), normalizeResult(result), trim(certificateNo),
                fallback(organization, "内部计量室"), fallback(operator, "计量员"), actualAt,
                validUntil, trim(attachmentRef), trim(remark), now, now, null);
    }

    public boolean expiresBefore(Instant threshold) {
        return validUntil != null && !validUntil.isAfter(threshold);
    }

    private static String normalizeResult(String value) {
        String result = fallback(value, "PASS").toUpperCase(Locale.ROOT);
        if (!result.equals("PASS") && !result.equals("FAIL") && !result.equals("LIMITED")) {
            throw new IllegalArgumentException("校准结论只能为PASS、FAIL或LIMITED");
        }
        return result;
    }
    private static String normalize(String value) { return required(value, "编码").toUpperCase(Locale.ROOT); }
    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }
    private static String fallback(String value, String replacement) { return value == null || value.isBlank() ? replacement : value.trim(); }
    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
