package com.bproject.ehm.workbench.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;
import java.util.Locale;

public record UserTask(
        String taskNo,
        String taskType,
        String sourceType,
        String sourceId,
        String title,
        String description,
        String assetCode,
        String assignee,
        String team,
        String priority,
        String status,
        Instant dueAt,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        Long version
) {
    public static UserTask create(String taskNo, String taskType, String sourceType, String sourceId,
                                  String title, String description, String assetCode, String assignee,
                                  String team, String priority, Instant dueAt, Instant now) {
        return new UserTask(normalize(taskNo), fallback(taskType, "人工任务"), fallback(sourceType, "MANUAL"),
                trim(sourceId), required(title, "待办标题"), trim(description), normalizeOptional(assetCode),
                fallback(assignee, "设备管理员"), fallback(team, "设备运维组"),
                fallback(priority, "P2 中"), "OPEN", dueAt, now, now, null, null);
    }

    public UserTask revise(String title, String description, String assetCode, String assignee,
                           String team, String priority, Instant dueAt, String status, Instant now) {
        if ("COMPLETED".equals(this.status) || "CANCELLED".equals(this.status)) {
            throw new DomainConflictException("已完成或已取消待办不能修改");
        }
        String nextStatus = fallback(status, this.status).toUpperCase(Locale.ROOT);
        if (!nextStatus.equals("OPEN") && !nextStatus.equals("IN_PROGRESS")) {
            throw new IllegalArgumentException("待办状态只能为OPEN或IN_PROGRESS");
        }
        return new UserTask(taskNo, taskType, sourceType, sourceId, required(title, "待办标题"),
                trim(description), normalizeOptional(assetCode), fallback(assignee, this.assignee),
                fallback(team, this.team), fallback(priority, this.priority), nextStatus, dueAt,
                createdAt, now, null, version);
    }

    public UserTask complete(String operator, Instant now) {
        if ("CANCELLED".equals(status)) throw new DomainConflictException("已取消待办不能完成");
        if ("COMPLETED".equals(status)) return this;
        return new UserTask(taskNo, taskType, sourceType, sourceId, title, description, assetCode,
                fallback(operator, assignee), team, priority, "COMPLETED", dueAt,
                createdAt, now, now, version);
    }

    private static String normalize(String value) { return required(value, "待办编号").toUpperCase(Locale.ROOT); }
    private static String normalizeOptional(String value) { return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT); }
    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }
    private static String fallback(String value, String replacement) { return value == null || value.isBlank() ? replacement : value.trim(); }
    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
