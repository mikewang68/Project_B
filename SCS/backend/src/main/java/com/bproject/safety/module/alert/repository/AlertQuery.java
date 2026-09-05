package com.bproject.safety.module.alert.repository;

/**
 * 告警列表查询条件（Backend Demo 版）。
 *
 * <p>兼容前端现有筛选参数名（risk/status/area/eventType/source/assignee/keyword/timeRange），
 * 同时兼容接口需求文档中的别名（level=risk、type=eventType、owner=assignee、from/to）。</p>
 */
public record AlertQuery(String keyword, String risk, String status, String area, String eventType,
                         String source, String assignee, String timeRange, String from, String to,
                         int page, int pageSize) {

    public static final String ALL = "全部";

    public static AlertQuery of(String keyword, String risk, String status, String area, String eventType,
                                String source, String assignee, String timeRange, String from, String to,
                                String levelAlias, String typeAlias, String ownerAlias,
                                Integer page, Integer pageSize) {
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 200);
        return new AlertQuery(blankToNull(keyword),
                firstNonAll(risk, levelAlias),
                firstNonAll(status, null),
                firstNonAll(area, null),
                firstNonAll(eventType, typeAlias),
                firstNonAll(source, null),
                firstNonAll(assignee, ownerAlias),
                firstNonAll(timeRange, null),
                blankToNull(from), blankToNull(to), p, size);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String firstNonAll(String v, String alias) {
        if (v != null && !v.isBlank() && !ALL.equals(v)) {
            return v.trim();
        }
        if (alias != null && !alias.isBlank() && !ALL.equals(alias)) {
            return alias.trim();
        }
        return null;
    }
}
