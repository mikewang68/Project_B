package com.bproject.safety.module.ai.repository;

/** AI 事件列表查询条件（字段对齐前端筛选区）。 */
public record AiEventQuery(String keyword, String type, String area, String camera,
                           String status, String risk, String confidence, String timeBucket,
                           int page, int pageSize) {

    public static AiEventQuery of(String keyword, String type, String area, String camera,
                                  String status, String risk, String confidence, String timeBucket,
                                  Integer page, Integer pageSize) {
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? 12 : Math.min(pageSize, 200);
        return new AiEventQuery(blank(keyword) ? null : keyword.trim(),
                blank(type) ? null : type, blank(area) ? null : area, blank(camera) ? null : camera,
                blank(status) ? null : status, blank(risk) ? null : risk,
                blank(confidence) ? null : confidence, blank(timeBucket) ? null : timeBucket, p, size);
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank() || "全部".equals(v) || "全部时段".equals(v);
    }
}
