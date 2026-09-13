package com.bproject.ehm.caseflow.application;

import com.bproject.ehm.alarm.domain.model.Alarm;
import com.bproject.ehm.alarm.ports.AlarmRepository;
import com.bproject.ehm.caseflow.domain.model.EvidenceItem;
import com.bproject.ehm.caseflow.domain.model.MaintenanceCase;
import com.bproject.ehm.caseflow.ports.MaintenanceCaseRepository;
import com.bproject.ehm.maintenance.application.WorkOrderApplicationService;
import com.bproject.ehm.maintenance.application.WorkOrderCommand;
import com.bproject.ehm.maintenance.application.WorkOrderView;
import com.bproject.ehm.maintenance.domain.model.WorkOrder;
import com.bproject.ehm.maintenance.domain.model.WorkOrderStatus;
import com.bproject.ehm.maintenance.ports.WorkOrderRepository;
import com.bproject.ehm.monitoring.application.DataQualityApplicationService;
import com.bproject.ehm.monitoring.application.DataQualityPointView;
import com.bproject.ehm.monitoring.application.DataQualitySummary;
import com.bproject.ehm.shared.error.DomainConflictException;
import com.bproject.ehm.shared.error.ResourceNotFoundException;
import com.bproject.ehm.shared.error.ValidationException;
import com.bproject.ehm.shared.page.PageQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class CaseFlowApplicationService {
    private final MaintenanceCaseRepository cases;
    private final AlarmRepository alarms;
    private final WorkOrderRepository workOrders;
    private final WorkOrderApplicationService workOrderService;
    private final DataQualityApplicationService dataQuality;
    private final Clock clock;

    @Autowired
    public CaseFlowApplicationService(MaintenanceCaseRepository cases, AlarmRepository alarms,
                                      WorkOrderRepository workOrders,
                                      WorkOrderApplicationService workOrderService,
                                      DataQualityApplicationService dataQuality) {
        this(cases, alarms, workOrders, workOrderService, dataQuality, Clock.systemUTC());
    }

    CaseFlowApplicationService(MaintenanceCaseRepository cases, AlarmRepository alarms,
                               WorkOrderRepository workOrders, WorkOrderApplicationService workOrderService,
                               DataQualityApplicationService dataQuality, Clock clock) {
        this.cases = cases;
        this.alarms = alarms;
        this.workOrders = workOrders;
        this.workOrderService = workOrderService;
        this.dataQuality = dataQuality;
        this.clock = clock;
    }

    public CaseFlowView getByAlarm(String alarmNo) {
        return CaseFlowView.from(findCaseByAlarm(alarmNo));
    }

    public CaseFlowView getByWorkOrder(String orderNo) {
        return CaseFlowView.from(cases.findByWorkOrderNo(orderNo)
                .orElseThrow(() -> new ResourceNotFoundException("工单尚未关联闭环档案：" + orderNo)));
    }

    public CaseFlowView openEvidenceCase(String alarmNo) {
        return cases.findByAlarmNo(alarmNo).map(CaseFlowView::from).orElseGet(() -> {
            Alarm alarm = findAlarm(alarmNo);
            Instant now = clock.instant();
            List<EvidenceItem> evidence = captureEvidence(alarm, now);
            MaintenanceCase opened = MaintenanceCase.open(alarm.alarmNo(), alarm.deviceCode(), alarm.deviceName(),
                    alarm.component(), alarm.level(), alarm.summary(), evidence, now);
            return CaseFlowView.from(cases.save(opened));
        });
    }

    public CaseFlowView diagnose(String alarmNo, String conclusion, String probableCause, String confidence,
                                 String evidence, String operator) {
        try {
            MaintenanceCase current = findCaseByAlarm(alarmNo);
            return CaseFlowView.from(cases.save(current.diagnose(conclusion, probableCause, confidence,
                    evidence, operator, clock.instant())));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public CaseFlowView convertToWorkOrder(String alarmNo, String title, String priority, String assignee,
                                           String description, String plannedWindow, String operator) {
        MaintenanceCase current = findCaseByAlarm(alarmNo);
        if (current.workOrderNo() != null && !current.workOrderNo().isBlank()) return CaseFlowView.from(current);
        Alarm alarm = findAlarm(alarmNo);
        Alarm acknowledged = alarm.acknowledge(operator, clock.instant());
        alarms.save(acknowledged);
        WorkOrderView created = workOrderService.create(new WorkOrderCommand(alarm.deviceCode(),
                fallback(title, alarm.component() + "异常处置"), fallback(priority, priorityOf(alarm.level())),
                fallback(assignee, alarm.assignee()), alarm.alarmNo(),
                fallback(description, "依据告警证据包和人工诊断执行检查、处置与维修后复测。"),
                fallback(plannedWindow, "待调度确认"), operator));
        return CaseFlowView.from(cases.save(current.linkWorkOrder(created.orderNo(), clock.instant())));
    }

    public CaseFlowView recordExecution(String orderNo, String action, String result,
                                        String safetyConfirmation, String partsUsed, String operator) {
        WorkOrder order = findWorkOrder(orderNo);
        if (order.status() != WorkOrderStatus.IN_PROGRESS) {
            throw new DomainConflictException("工单状态必须为“执行中”才能记录维修执行过程，当前为“"
                    + order.status().label() + "”");
        }
        try {
            MaintenanceCase current = findCaseByWorkOrder(orderNo);
            return CaseFlowView.from(cases.save(current.recordExecution(action, result, safetyConfirmation,
                    partsUsed, operator, clock.instant())));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public CaseFlowView recordRetest(String orderNo, String pointCode, Double beforeValue, Double afterValue,
                                     String unit, String criterion, boolean passed, String operator) {
        WorkOrder order = findWorkOrder(orderNo);
        if (order.status() != WorkOrderStatus.WAITING_VERIFY) {
            throw new DomainConflictException("工单状态必须为“待复测”才能提交复测结果，当前为“"
                    + order.status().label() + "”");
        }
        try {
            MaintenanceCase current = findCaseByWorkOrder(orderNo);
            return CaseFlowView.from(cases.save(current.recordRetest(pointCode, beforeValue, afterValue,
                    unit, criterion, passed, operator, clock.instant())));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public CaseFlowView closeLoop(String orderNo, String conclusion, String operator) {
        WorkOrder order = findWorkOrder(orderNo);
        if (order.status() != WorkOrderStatus.WAITING_VERIFY) {
            throw new DomainConflictException("工单状态必须为“待复测”才能闭环，当前为“"
                    + order.status().label() + "”");
        }
        MaintenanceCase current = findCaseByWorkOrder(orderNo);
        Instant now = clock.instant();
        MaintenanceCase closedCase;
        try {
            closedCase = current.close(operator, conclusion, now);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }

        Alarm alarm = findAlarm(current.alarmNo());
        Alarm acknowledged = alarm.acknowledge(operator, now);
        WorkOrder closedOrder = order.transitionTo(WorkOrderStatus.CLOSED, operator,
                "维修后复测通过，完成闭环", now);

        // Demo使用同库顺序写入；生产拆分服务后改为Outbox事件+幂等消费者，避免跨服务分布式事务。
        workOrders.save(closedOrder);
        alarms.save(acknowledged.close(operator, conclusion, now));
        return CaseFlowView.from(cases.save(closedCase));
    }

    private List<EvidenceItem> captureEvidence(Alarm alarm, Instant now) {
        List<EvidenceItem> values = new ArrayList<>();
        values.add(new EvidenceItem("ALARM", "告警事件", alarm.level() + "；" + alarm.summary(),
                alarm.alarmNo(), now));
        values.add(new EvidenceItem("CONTEXT", "触发上下文",
                "触发方式：" + fallback(alarm.triggerMethod(), "未标注") + "；SLA："
                        + fallback(alarm.slaText(), "未配置") + "；发生时间：" + alarm.occurredAt(),
                alarm.alarmNo(), now));
        try {
            DataQualitySummary summary = dataQuality.summary(alarm.deviceCode());
            values.add(new EvidenceItem("DATA_QUALITY", "数据质量准入",
                    "启用测点" + summary.enabledPoints() + "；正常" + summary.goodPoints()
                            + "；断流" + summary.missingPoints() + "；越界" + summary.outOfRangePoints()
                            + "；可用率" + summary.availabilityPercent() + "%；健康评估"
                            + (summary.healthAssessmentAllowed() ? "允许" : "暂停/降置信度"),
                    alarm.deviceCode(), now));
            List<DataQualityPointView> points = dataQuality.list(alarm.deviceCode(), new PageQuery(0, 200), null).content();
            for (DataQualityPointView point : points) {
                values.add(new EvidenceItem("POINT_SNAPSHOT", point.pointCode() + " · " + point.name(),
                        "值=" + (point.lastValue() == null ? "缺失" : point.lastValue()) + point.unit()
                                + "；质量=" + point.qualityLabel() + "；源时间=" + point.sourceTimestamp()
                                + "；判定=" + point.message(), point.pointCode(), now));
            }
        } catch (RuntimeException exception) {
            values.add(new EvidenceItem("DATA_QUALITY", "数据质量快照不可用",
                    "生成证据包时未取得测点质量数据：" + exception.getMessage(), alarm.deviceCode(), now));
        }
        return values;
    }

    private Alarm findAlarm(String alarmNo) {
        return alarms.findByAlarmNo(alarmNo)
                .orElseThrow(() -> new ResourceNotFoundException("未找到告警：" + alarmNo));
    }

    private WorkOrder findWorkOrder(String orderNo) {
        return workOrders.findByOrderNo(orderNo)
                .orElseThrow(() -> new ResourceNotFoundException("未找到工单：" + orderNo));
    }

    private MaintenanceCase findCaseByAlarm(String alarmNo) {
        return cases.findByAlarmNo(alarmNo)
                .orElseThrow(() -> new ResourceNotFoundException("告警尚未生成闭环档案：" + alarmNo));
    }

    private MaintenanceCase findCaseByWorkOrder(String orderNo) {
        return cases.findByWorkOrderNo(orderNo)
                .orElseThrow(() -> new ResourceNotFoundException("工单尚未关联闭环档案：" + orderNo));
    }

    private String priorityOf(String level) {
        return level != null && (level.contains("L3") || level.contains("L4")) ? "P1 高" : "P2 中";
    }

    private String fallback(String value, String replacement) {
        return value == null || value.isBlank() ? replacement : value.trim();
    }
}
