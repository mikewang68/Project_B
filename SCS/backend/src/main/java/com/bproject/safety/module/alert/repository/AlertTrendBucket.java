package com.bproject.safety.module.alert.repository;

/**
 * 复查②：告警趋势桶（态势首页/大屏趋势曲线的数据契约）。
 * label 为展示标签（小时桶 "HH:00"、日桶 "MM/dd"，均为 Asia/Shanghai）；
 * high 为 SEVERE/URGENT 高风险计数。
 */
public record AlertTrendBucket(String label, long total, long high) {
}
