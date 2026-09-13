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
        return new InspectionItemResult(itemId, name, method, standard, unit, required,
                value == null ? "" : value.trim(), normalized, remark == null ? "" : remark.trim());
    }
}
