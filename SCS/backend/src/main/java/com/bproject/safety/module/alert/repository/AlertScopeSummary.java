package com.bproject.safety.module.alert.repository;

import java.util.Map;

/**
 * 告警作用域聚合摘要（压测打磨 / P0-4 深化）。
 *
 * <p>语义与 {@link AlertRepository#filter(AlertQuery)} 全量行内存聚合严格一致：
 * high = 风险 SEVERE/URGENT；byArea 键为区域名快照（空串兜底）；byTarget 键为目标名快照
 * （空串兜底，供"重复风险事件"KPI 分组）；byEventType 键为 COALESCE(event_type, title)。
 * JDBC 实现为 8 个列过滤后的 GROUP BY / 标量聚合查询，任何数据规模下均为毫秒级，
 * 替代"全窗口整表加载 + Java 聚合"的路径。</p>
 */
public record AlertScopeSummary(
        long total,
        long severeOpen,
        long urgentOpen,
        Map<String, Long> byStatus,
        Map<String, Long> byRisk,
        Map<String, Long> byArea,
        Map<String, Long> byAreaHigh,
        Map<String, Long> byTarget,
        Map<String, Long> byEventType,
        Map<String, Long> byEventTypeHigh,
        Long avgConfirmSeconds,
        Long avgCloseSeconds) {

    /** 高风险总量（scope 内 SEVERE+URGENT，不限状态）。 */
    public long highTotal() {
        return byRisk.getOrDefault("SEVERE", 0L) + byRisk.getOrDefault("URGENT", 0L);
    }

    /** 重复风险事件数：同一目标触发 ≥2 次的事件总数之和。 */
    public long repeatedTotal() {
        return byTarget.values().stream().filter(c -> c >= 2).mapToLong(Long::longValue).sum();
    }
}
