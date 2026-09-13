package com.bproject.ehm.maintenance.domain.model;

public record InspectionTemplateItem(
        String itemId,
        String name,
        String method,
        String standard,
        String unit,
        boolean required
) {
    public InspectionTemplateItem {
        itemId = required(itemId, "检查项编号");
        name = required(name, "检查项名称");
        method = fallback(method, "现场检查");
        standard = required(standard, "验收标准");
        unit = fallback(unit, "—");
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private static String fallback(String value, String replacement) {
        return value == null || value.isBlank() ? replacement : value.trim();
    }
}
