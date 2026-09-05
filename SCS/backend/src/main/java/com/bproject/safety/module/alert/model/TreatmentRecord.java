package com.bproject.safety.module.alert.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 现场处置结果，字段与前端 TreatmentResult 对齐。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TreatmentRecord(java.util.List<String> measures, String result, String attachment,
                              String note, String submitTime, String handler) {
}
