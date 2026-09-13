package com.bproject.ehm.maintenance.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public record InspectionTask(
        String taskNo,
        String planId,
        String assetCode,
        String assetName,
        String componentCode,
        String title,
        Instant scheduledAt,
        String assignee,
        InspectionTaskStatus status,
        List<InspectionItemResult> checklist,
        Instant startedAt,
        Instant submittedAt,
        String conclusion,
        String operator,
        String defectNo,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public InspectionTask {
        checklist = checklist == null ? List.of() : List.copyOf(checklist);
        status = status == null ? InspectionTaskStatus.PLANNED : status;
    }

    public static InspectionTask create(String taskNo, MaintenancePlan plan, Instant scheduledAt,
                                        String assignee, Instant now) {
        return new InspectionTask(required(taskNo, "任务编号"), plan.planId(), plan.assetCode(),
                plan.assetName(), plan.componentCode(), plan.name(),
                scheduledAt == null ? plan.nextDueAt() : scheduledAt,
                fallback(assignee, plan.ownerTeam()), InspectionTaskStatus.PLANNED,
                plan.checklistTemplate().stream().map(InspectionItemResult::pending).toList(),
                null, null, null, null, null, now, now, null);
    }

    public InspectionTask start(String operator, Instant now) {
        if (status != InspectionTaskStatus.PLANNED) {
            throw new DomainConflictException("只有待执行任务可以开始，当前为“" + status.label() + "”");
        }
        return copy(InspectionTaskStatus.IN_PROGRESS, checklist, now, null,
                null, normalizeOperator(operator), defectNo, now);
    }

    public InspectionTask submit(List<ItemSubmission> submissions, String conclusion,
                                 String operator, Instant now) {
        if (status != InspectionTaskStatus.IN_PROGRESS) {
            throw new DomainConflictException("只有执行中的点检任务可以提交结果");
        }
        Map<String, ItemSubmission> byId = submissions == null ? Map.of() : submissions.stream()
                .collect(Collectors.toMap(ItemSubmission::itemId, Function.identity(), (a, b) -> b));
        List<InspectionItemResult> completed = new ArrayList<>();
        for (InspectionItemResult item : checklist) {
            ItemSubmission value = byId.get(item.itemId());
            if (value == null) {
                if (item.required()) throw new IllegalArgumentException("必检项未填写：" + item.name());
                completed.add(item.complete("", "NA", "未检查"));
            } else {
                completed.add(item.complete(value.measuredValue(), value.result(), value.remark()));
            }
        }
        boolean failed = completed.stream().anyMatch(item -> "FAIL".equals(item.result()));
        return copy(failed ? InspectionTaskStatus.DEFECT_FOUND : InspectionTaskStatus.CLOSED,
                completed, startedAt, now, required(conclusion, "点检结论"),
                normalizeOperator(operator), defectNo, now);
    }

    public InspectionTask linkDefect(String value, Instant now) {
        if (status != InspectionTaskStatus.DEFECT_FOUND) {
            throw new DomainConflictException("只有发现异常的点检任务可以关联缺陷");
        }
        if (defectNo != null && !defectNo.isBlank() && !defectNo.equals(value)) {
            throw new DomainConflictException("点检任务已关联缺陷：" + defectNo);
        }
        return copy(status, checklist, startedAt, submittedAt, conclusion, operator,
                required(value, "缺陷编号"), now);
    }

    public List<InspectionItemResult> failedItems() {
        return checklist.stream().filter(item -> "FAIL".equals(item.result())).toList();
    }

    private InspectionTask copy(InspectionTaskStatus target, List<InspectionItemResult> nextChecklist,
                                Instant nextStarted, Instant nextSubmitted, String nextConclusion,
                                String nextOperator, String nextDefectNo, Instant now) {
        return new InspectionTask(taskNo, planId, assetCode, assetName, componentCode, title,
                scheduledAt, assignee, target, nextChecklist, nextStarted, nextSubmitted,
                nextConclusion, nextOperator, nextDefectNo, createdAt, now, version);
    }

    public record ItemSubmission(String itemId, String measuredValue, String result, String remark) {
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private static String fallback(String value, String replacement) {
        return value == null || value.isBlank() ? replacement : value.trim();
    }

    private static String normalizeOperator(String value) {
        return fallback(value, "Demo点检员");
    }
}
