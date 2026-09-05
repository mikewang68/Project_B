package com.bproject.safety.module.alert.service;

/** 告警顶部指标（由 Repository 数据实时计算，不返回写死数字）。 */
public record AlertMetrics(long total, long pending, long active, long severe, long urgent, long closed) {
}
