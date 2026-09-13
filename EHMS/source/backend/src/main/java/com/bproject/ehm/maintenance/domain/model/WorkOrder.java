package com.bproject.ehm.maintenance.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record WorkOrder(
        String orderNo,
        String deviceCode,
        String deviceName,
        String title,
        String priority,
        WorkOrderStatus status,
        String assignee,
        String source,
        String description,
        String plannedWindow,
        Instant createdAt,
        Instant updatedAt,
        List<WorkOrderTransition> history,
        Long version
) {
    private static final Map<WorkOrderStatus, Set<WorkOrderStatus>> TRANSITIONS = Map.of(
            WorkOrderStatus.DRAFT, EnumSet.of(WorkOrderStatus.SUBMITTED, WorkOrderStatus.CANCELLED),
            WorkOrderStatus.SUBMITTED, EnumSet.of(WorkOrderStatus.APPROVED, WorkOrderStatus.REJECTED, WorkOrderStatus.CANCELLED),
            WorkOrderStatus.APPROVED, EnumSet.of(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.ON_HOLD, WorkOrderStatus.CANCELLED),
            WorkOrderStatus.IN_PROGRESS, EnumSet.of(WorkOrderStatus.WAITING_VERIFY, WorkOrderStatus.ON_HOLD),
            WorkOrderStatus.WAITING_VERIFY, EnumSet.of(WorkOrderStatus.CLOSED, WorkOrderStatus.IN_PROGRESS),
            WorkOrderStatus.REJECTED, EnumSet.of(WorkOrderStatus.DRAFT, WorkOrderStatus.CANCELLED),
            WorkOrderStatus.ON_HOLD, EnumSet.of(WorkOrderStatus.APPROVED, WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.CANCELLED),
            WorkOrderStatus.CLOSED, EnumSet.noneOf(WorkOrderStatus.class),
            WorkOrderStatus.CANCELLED, EnumSet.noneOf(WorkOrderStatus.class)
    );

    public WorkOrder {
        history = history == null ? List.of() : List.copyOf(history);
    }

    public static WorkOrder create(String orderNo, String deviceCode, String deviceName, String title,
                                   String priority, String assignee, String source, String description,
                                   String plannedWindow, String operator, Instant now) {
        WorkOrderTransition initial = new WorkOrderTransition(WorkOrderStatus.DRAFT, WorkOrderStatus.SUBMITTED,
                normalizeOperator(operator), "创建并提交工单", now);
        return new WorkOrder(orderNo, deviceCode, deviceName, required(title, "工单主题"),
                fallback(priority, "P2 中"), WorkOrderStatus.SUBMITTED, fallback(assignee, "待分配"),
                fallback(source, "人工创建"), fallback(description, ""), fallback(plannedWindow, "待确认"),
                now, now, List.of(initial), null);
    }

    public WorkOrder transitionTo(WorkOrderStatus target, String operator, String reason, Instant now) {
        if (target == status) return this;
        if (!TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
            throw new DomainConflictException("工单不能从“" + status.label() + "”直接推进到“" + target.label() + "”");
        }
        List<WorkOrderTransition> nextHistory = new ArrayList<>(history);
        nextHistory.add(new WorkOrderTransition(status, target, normalizeOperator(operator),
                fallback(reason, "人工推进状态"), now));
        return new WorkOrder(orderNo, deviceCode, deviceName, title, priority, target, assignee,
                source, description, plannedWindow, createdAt, now, nextHistory, version);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String normalizeOperator(String operator) {
        return fallback(operator, "Demo设备管理员");
    }
}
