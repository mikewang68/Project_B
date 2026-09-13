package com.bproject.ehm.spares.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

public record SparePart(
        String partCode,
        String name,
        String specification,
        String category,
        String unit,
        List<String> compatibleAssets,
        double safetyStock,
        double reorderPoint,
        int leadTimeDays,
        BigDecimal unitCost,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public SparePart {
        compatibleAssets = compatibleAssets == null ? List.of() : List.copyOf(compatibleAssets);
    }

    public static SparePart create(String partCode, String name, String specification,
                                   String category, String unit, List<String> compatibleAssets,
                                   double safetyStock, double reorderPoint, int leadTimeDays,
                                   BigDecimal unitCost, Instant now) {
        if (safetyStock < 0 || reorderPoint < 0) throw new IllegalArgumentException("安全库存和补货点不能为负数");
        if (leadTimeDays < 0 || leadTimeDays > 3650) throw new IllegalArgumentException("采购提前期必须为0～3650天");
        if (unitCost != null && unitCost.signum() < 0) throw new IllegalArgumentException("备件单价不能为负数");
        return new SparePart(normalize(partCode), required(name, "备件名称"),
                fallback(specification, "规格待确认"), fallback(category, "通用备件"),
                fallback(unit, "件"), compatibleAssets, safetyStock, reorderPoint,
                leadTimeDays, unitCost == null ? BigDecimal.ZERO : unitCost,
                true, now, now, null);
    }

    public static String normalize(String value) {
        return required(value, "备件编码").toUpperCase(Locale.ROOT);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private static String fallback(String value, String replacement) {
        return value == null || value.isBlank() ? replacement : value.trim();
    }
}
