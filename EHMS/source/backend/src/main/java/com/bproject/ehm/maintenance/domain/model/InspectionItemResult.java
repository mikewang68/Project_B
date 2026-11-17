package com.bproject.ehm.maintenance.domain.model;

public record InspectionItemResult(
        String itemId,
        String name,
        String method,
        String standard,
        String unit,
        boolean required,
        String measuredValue,
        String result,
        String remark
) {
    public static InspectionItemResult pending(InspectionTemplateItem item) {
        return new InspectionItemResult(item.itemId(), item.name(), item.method(), item.standard(),
                item.unit(), item.required(), null, "PENDING", null);
    }

    public InspectionItemResult complete(String value, String result, String remark) {
        String normalized = result == null ? "" : result.trim().toUpperCase();
        if (!"PASS".equals(normalized) && !"FAIL".equals(normalized) && !"NA".equals(normalized)) {
            throw new IllegalArgumentException("检查结果仅允许 PASS、FAIL 或 NA");
        }
        if (required && "NA".equals(normalized)) throw new IllegalArgumentException(name + "为必检项，不能填写NA");
        if (required && (value==null||value.isBlank()) && (remark==null||remark.isBlank()))
            throw new IllegalArgumentException(name + "为必检项，请填写实际测量值或现场观察依据，不能仅勾选通过");
        return new InspectionItemResult(itemId, name, method, standard, unit, required,
                value == null ? "" : value.trim(), normalized, remark == null ? "" : remark.trim());
    }
}
