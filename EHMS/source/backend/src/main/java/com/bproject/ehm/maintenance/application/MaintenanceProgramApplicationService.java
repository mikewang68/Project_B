package com.bproject.ehm.maintenance.application;

import com.bproject.ehm.asset.application.AssetQueryFacade;
import com.bproject.ehm.asset.application.DeviceView;
import com.bproject.ehm.asset.domain.model.Asset;
import com.bproject.ehm.maintenance.domain.model.DefectRecord;
import com.bproject.ehm.maintenance.domain.model.InspectionItemResult;
import com.bproject.ehm.maintenance.domain.model.InspectionTask;
import com.bproject.ehm.maintenance.domain.model.InspectionTemplateItem;
import com.bproject.ehm.maintenance.domain.model.MaintenancePlan;
import com.bproject.ehm.maintenance.ports.DefectRepository;
import com.bproject.ehm.maintenance.ports.InspectionTaskRepository;
import com.bproject.ehm.maintenance.ports.MaintenancePlanRepository;
import com.bproject.ehm.shared.error.DomainConflictException;
import com.bproject.ehm.shared.error.ResourceNotFoundException;
import com.bproject.ehm.shared.error.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MaintenanceProgramApplicationService {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final MaintenancePlanRepository plans;
    private final InspectionTaskRepository tasks;
    private final DefectRepository defects;
    private final AssetQueryFacade assets;
    private final WorkOrderApplicationService workOrders;
    private final Clock clock;

    @Autowired
    public MaintenanceProgramApplicationService(MaintenancePlanRepository plans,
                                                InspectionTaskRepository tasks,
                                                DefectRepository defects,
                                                AssetQueryFacade assets,
                                                WorkOrderApplicationService workOrders) {
        this(plans, tasks, defects, assets, workOrders, Clock.systemUTC());
    }

    MaintenanceProgramApplicationService(MaintenancePlanRepository plans,
                                         InspectionTaskRepository tasks,
                                         DefectRepository defects,
                                         AssetQueryFacade assets,
                                         WorkOrderApplicationService workOrders,
                                         Clock clock) {
        this.plans = plans;
        this.tasks = tasks;
        this.defects = defects;
        this.assets = assets;
        this.workOrders = workOrders;
        this.clock = clock;
    }

    public List<MaintenancePlan> listPlans(String assetCode) {
        String code = requireAsset(assetCode);
        return plans.findByAssetCode(code);
    }

    public MaintenancePlan createPlan(String assetCode, String componentCode, String name,
                                      String strategyType, int cycleDays, String triggerCondition,
                                      List<InspectionTemplateItem> checklist, String ownerTeam,
                                      Instant nextDueAt) {
        try {
            String code = requireAsset(assetCode);
            DeviceView asset = assets.get(code);
            Instant now = clock.instant();
            MaintenancePlan value = MaintenancePlan.create("MPL-" + code.replace("-", "") + "-" + shortId(),
                    code, asset.name(), componentCode, name, strategyType, cycleDays,
                    triggerCondition, checklist, ownerTeam, nextDueAt, now);
            return plans.save(value);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public InspectionTask generateTask(String planId, Instant scheduledAt, String assignee) {
        MaintenancePlan plan = findPlan(planId);
        if (tasks.hasOpenTaskForPlan(planId)) {
            throw new DomainConflictException("该计划已有待执行或执行中的点检任务，不能重复生成");
        }
        Instant now = clock.instant();
        InspectionTask task = InspectionTask.create("INS-" + DAY.format(now) + "-" + shortId(),
                plan, scheduledAt, assignee, now);
        InspectionTask saved = tasks.save(task);
        plans.save(plan.markGenerated(now));
        return saved;
    }

    public List<InspectionTask> listTasks(String assetCode) {
        return tasks.findByAssetCode(requireAsset(assetCode));
    }

    public InspectionTask getTask(String taskNo) {
        return tasks.findByTaskNo(taskNo)
                .orElseThrow(() -> new ResourceNotFoundException("未找到点检任务：" + taskNo));
    }

    public InspectionTask startTask(String taskNo, String operator) {
        return tasks.save(getTask(taskNo).start(operator, clock.instant()));
    }

    public InspectionSubmissionResult submitTask(String taskNo,
                                                 List<InspectionTask.ItemSubmission> items,
                                                 String conclusion, String operator,
                                                 String defectSeverity, String defectDescription) {
        try {
            Instant now = clock.instant();
            InspectionTask submitted = getTask(taskNo).submit(items, conclusion, operator, now);
            if (submitted.failedItems().isEmpty()) {
                return new InspectionSubmissionResult(tasks.save(submitted), null);
            }
            String evidence = submitted.failedItems().stream()
                    .map(this::failureEvidence).collect(Collectors.joining("；"));
            DefectRecord defect = DefectRecord.open("DEF-" + DAY.format(now) + "-" + shortId(),
                    submitted, defectSeverity,
                    fallback(defectDescription, "点检发现" + submitted.failedItems().size() + "项不符合标准"),
                    evidence, operator, now);
            DefectRecord savedDefect = defects.save(defect);
            InspectionTask savedTask = tasks.save(submitted.linkDefect(savedDefect.defectNo(), now));
            return new InspectionSubmissionResult(savedTask, savedDefect);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public List<DefectRecord> listDefects(String assetCode) {
        return defects.findByAssetCode(requireAsset(assetCode));
    }

    public DefectRecord createDefectWorkOrder(String defectNo, String title, String assignee,
                                              String plannedWindow, String operator) {
        DefectRecord defect = defects.findByDefectNo(defectNo)
                .orElseThrow(() -> new ResourceNotFoundException("未找到缺陷：" + defectNo));
        if (defect.workOrderNo() != null && !defect.workOrderNo().isBlank()) return defect;
        WorkOrderView order = workOrders.create(new WorkOrderCommand(defect.assetCode(),
                fallback(title, defect.assetName() + "点检缺陷处置"), priorityOf(defect.severity()),
                fallback(assignee, "设备机修班"), "点检缺陷 " + defect.defectNo(),
                defect.description() + "；依据：" + defect.evidenceSummary(),
                fallback(plannedWindow, "待调度确认"), fallback(operator, "Demo设备工程师")));
        return defects.save(defect.linkWorkOrder(order.orderNo(), clock.instant()));
    }

    private MaintenancePlan findPlan(String planId) {
        return plans.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("未找到维护计划：" + planId));
    }

    private String requireAsset(String assetCode) {
        String code = Asset.normalizeCode(assetCode);
        if (!assets.exists(code)) throw new ResourceNotFoundException("未找到设备：" + code);
        return code;
    }

    private String failureEvidence(InspectionItemResult item) {
        return item.name() + "=" + fallback(item.measuredValue(), "未填写数值") + item.unit()
                + "，标准：" + item.standard() + "，备注：" + fallback(item.remark(), "无");
    }

    private String priorityOf(String severity) {
        return severity != null && (severity.contains("重大") || severity.contains("严重")) ? "P1 高" : "P2 中";
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private String fallback(String value, String replacement) {
        return value == null || value.isBlank() ? replacement : value.trim();
    }
}
