package com.bproject.ehm.maintenance.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;

public record DefectRecord(
        String defectNo,
        String taskNo,
        String assetCode,
        String assetName,
        String componentCode,
        String severity,
        String description,
        String evidenceSummary,
        String status,
        String workOrderNo,
        String reporter,
        Instant discoveredAt,
        Instant updatedAt,
        Long version
) {
    public static DefectRecord open(String defectNo, InspectionTask task, String severity,
                                    String description, String evidenceSummary,
                                    String reporter, Instant now) {
        return new DefectRecord(required(defectNo, "缺陷编号"), task.taskNo(), task.assetCode(),
                task.assetName(), task.componentCode(), fallback(severity, "一般"),
                required(description, "缺陷描述"), required(evidenceSummary, "缺陷依据"),
                "OPEN", null, fallback(reporter, "Demo点检员"), now, now, null);
    }

    public DefectRecord linkWorkOrder(String orderNo, Instant now) {
        if (workOrderNo != null && !workOrderNo.isBlank()) {
            if (workOrderNo.equals(orderNo)) return this;
            throw new DomainConflictException("缺陷已关联工单：" + workOrderNo);
        }
        return new DefectRecord(defectNo, taskNo, assetCode, assetName, componentCode,
                severity, description, evidenceSummary, "WORK_ORDER_CREATED",
                required(orderNo, "工单编号"), reporter, discoveredAt, now, version);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private static String fallback(String value, String replacement) {
        return value == null || value.isBlank() ? replacement : value.trim();
    }
}
