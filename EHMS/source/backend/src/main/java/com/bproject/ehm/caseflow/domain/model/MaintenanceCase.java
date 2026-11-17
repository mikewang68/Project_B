package com.bproject.ehm.caseflow.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record MaintenanceCase(
        String alarmNo,
        String deviceCode,
        String deviceName,
        String component,
        String alarmLevel,
        String alarmSummary,
        CaseStatus status,
        List<EvidenceItem> evidence,
        List<DiagnosisRecord> diagnoses,
        String workOrderNo,
        List<ExecutionRecord> executionRecords,
        List<RetestRecord> retests,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt,
        Long version
) {
    public MaintenanceCase {
        evidence = immutable(evidence);
        diagnoses = immutable(diagnoses);
        executionRecords = immutable(executionRecords);
        retests = immutable(retests);
        status = status == null ? CaseStatus.OPEN : status;
    }

    public static MaintenanceCase open(String alarmNo, String deviceCode, String deviceName, String component,
                                       String alarmLevel, String alarmSummary, List<EvidenceItem> evidence,
                                       Instant now) {
        return new MaintenanceCase(required(alarmNo, "告警编号"), required(deviceCode, "设备编码"),
                required(deviceName, "设备名称"), fallback(component, "设备本体"),
                fallback(alarmLevel, "待分级"), required(alarmSummary, "告警摘要"), CaseStatus.OPEN,
                evidence, List.of(), null, List.of(), List.of(), now, now, null, null);
    }

    public MaintenanceCase diagnose(String conclusion, String probableCause, String confidence,
                                    String evidenceText, String operator, Instant now) {
        ensureOpen();
        List<DiagnosisRecord> next = new ArrayList<>(diagnoses);
        next.add(new DiagnosisRecord("DIA-" + alarmNo + "-" + (next.size() + 1),
                required(conclusion, "诊断结论"), required(probableCause, "可能原因"),
                fallback(confidence, "人工判断"), required(evidenceText, "诊断依据"),
                normalizeOperator(operator), now));
        return copy(workOrderNo==null?CaseStatus.DIAGNOSED:status, next, workOrderNo, executionRecords, retests, now, null);
    }

    public MaintenanceCase linkWorkOrder(String orderNo, Instant now) {
        ensureOpen();
        if (diagnoses.isEmpty()) throw new DomainConflictException("至少记录一条人工诊断后才能转工单");
        if (workOrderNo != null && !workOrderNo.isBlank()) {
            if (workOrderNo.equals(orderNo)) return this;
            throw new DomainConflictException("该告警已关联工单：" + workOrderNo);
        }
        return copy(CaseStatus.WORK_ORDER_CREATED, diagnoses, required(orderNo, "工单编号"),
                executionRecords, retests, now, null);
    }

    public MaintenanceCase recordExecution(String action, String result, String safetyConfirmation,
                                           String partsUsed, String operator, Instant now) {
        ensureWorkOrderLinked();
        List<ExecutionRecord> next = new ArrayList<>(executionRecords);
        next.add(new ExecutionRecord("EXE-" + alarmNo + "-" + (next.size() + 1),
                required(action, "执行内容"), required(result, "执行结果"),
                required(safetyConfirmation, "安全确认"), fallback(partsUsed, "未使用备件"),
                normalizeOperator(operator), now));
        return copy(CaseStatus.EXECUTING, diagnoses, workOrderNo, next, retests, now, null);
    }

    public MaintenanceCase recordRetest(String pointCode, Double beforeValue, Double afterValue, String unit,
                                        String criterion, boolean passed, String operator, Instant now) {
        ensureWorkOrderLinked();
        if (executionRecords.isEmpty()) throw new DomainConflictException("必须先记录维修执行过程，再提交复测结果");
        if (beforeValue == null || afterValue == null || !Double.isFinite(beforeValue) || !Double.isFinite(afterValue))
            throw new IllegalArgumentException("维修前、维修后数值须为有效有限数值");
        List<RetestRecord> next = new ArrayList<>(retests);
        next.add(new RetestRecord("RET-" + alarmNo + "-" + (next.size() + 1),
                required(pointCode, "复测测点"), beforeValue, afterValue, fallback(unit, "—"),
                required(criterion, "验收判据"), passed, normalizeOperator(operator), now));
        CaseStatus target = passed ? CaseStatus.VERIFIED : CaseStatus.WAITING_RETEST;
        return copy(target, diagnoses, workOrderNo, executionRecords, next, now, null);
    }

    public MaintenanceCase close(String operator, String conclusion, Instant now) {
        ensureWorkOrderLinked();
        if (executionRecords.isEmpty()) throw new DomainConflictException("缺少维修执行记录，不能闭环");
        java.util.Map<String,RetestRecord> latest=new java.util.LinkedHashMap<>();
        Instant roundStart=executionRecords.get(executionRecords.size()-1).executedAt();
        for(var retest:retests)if(!retest.testedAt().isBefore(roundStart))latest.put(retest.pointCode(),retest);
        var plan=evidence.stream().filter(e->e.type().equals("RETEST_PLAN")).reduce((a,b)->b);
        if(plan.isPresent() && java.util.Arrays.stream(plan.get().content().split(",")).anyMatch(p->!latest.containsKey(p)))
            throw new DomainConflictException("复测计划中的必测项尚未全部完成，不能闭环");
        if (status!=CaseStatus.VERIFIED || latest.isEmpty() || latest.values().stream().anyMatch(r->!r.passed()))
            throw new DomainConflictException("各已复测测点的最新结果必须全部合格，且当前维修轮次已验证后才能闭环；不合格须返修或继续复测");
        List<EvidenceItem> nextEvidence = new ArrayList<>(evidence);
        nextEvidence.add(new EvidenceItem("CLOSURE", "闭环结论",
                required(conclusion, "闭环结论") + "；签核人：" + normalizeOperator(operator),
                workOrderNo, now));
        return new MaintenanceCase(alarmNo, deviceCode, deviceName, component, alarmLevel, alarmSummary,
                CaseStatus.CLOSED, nextEvidence, diagnoses, workOrderNo, executionRecords, retests,
                createdAt, now, now, version);
    }

    public MaintenanceCase appendEvidence(EvidenceItem item, Instant now) {
        ensureOpen();var next=new ArrayList<>(evidence);next.add(item);
        return new MaintenanceCase(alarmNo,deviceCode,deviceName,component,alarmLevel,alarmSummary,status,
                next,diagnoses,workOrderNo,executionRecords,retests,createdAt,now,closedAt,version);
    }

    public MaintenanceCase withVersion(long value){return new MaintenanceCase(alarmNo,deviceCode,deviceName,component,alarmLevel,alarmSummary,
        status,evidence,diagnoses,workOrderNo,executionRecords,retests,createdAt,updatedAt,closedAt,value);}

    private MaintenanceCase copy(CaseStatus target, List<DiagnosisRecord> nextDiagnoses, String nextOrderNo,
                                 List<ExecutionRecord> nextExecution, List<RetestRecord> nextRetests,
                                 Instant now, Instant nextClosedAt) {
        return new MaintenanceCase(alarmNo, deviceCode, deviceName, component, alarmLevel, alarmSummary,
                target, evidence, nextDiagnoses, nextOrderNo, nextExecution, nextRetests,
                createdAt, now, nextClosedAt, version);
    }

    private void ensureOpen() {
        if (status == CaseStatus.CLOSED) throw new DomainConflictException("闭环档案已关闭，不能继续修改");
    }

    private void ensureWorkOrderLinked() {
        ensureOpen();
        if (workOrderNo == null || workOrderNo.isBlank()) throw new DomainConflictException("尚未关联维保工单");
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String normalizeOperator(String value) {
        return fallback(value, "Demo设备管理员");
    }
}
