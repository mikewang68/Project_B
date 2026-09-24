package com.bproject.ehm.workbench.application;

import com.bproject.ehm.alarm.domain.model.AlarmStatus;
import com.bproject.ehm.alarm.ports.AlarmRepository;
import com.bproject.ehm.maintenance.domain.model.WorkOrderStatus;
import com.bproject.ehm.maintenance.ports.WorkOrderRepository;
import com.bproject.ehm.shared.error.DomainConflictException;
import com.bproject.ehm.shared.error.ResourceNotFoundException;
import com.bproject.ehm.shared.error.ValidationException;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.workbench.domain.model.ShiftHandover;
import com.bproject.ehm.workbench.domain.model.UserTask;
import com.bproject.ehm.workbench.ports.ShiftHandoverRepository;
import com.bproject.ehm.workbench.ports.UserTaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class WorkbenchApplicationService {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private final UserTaskRepository tasks;
    private final ShiftHandoverRepository handovers;
    private final AlarmRepository alarms;
    private final WorkOrderRepository workOrders;
    private final Clock clock;

    @Autowired
    public WorkbenchApplicationService(UserTaskRepository tasks, ShiftHandoverRepository handovers,
                                       AlarmRepository alarms, WorkOrderRepository workOrders) {
        this(tasks, handovers, alarms, workOrders, Clock.systemUTC());
    }

    WorkbenchApplicationService(UserTaskRepository tasks, ShiftHandoverRepository handovers,
                                AlarmRepository alarms, WorkOrderRepository workOrders, Clock clock) {
        this.tasks = tasks;
        this.handovers = handovers;
        this.alarms = alarms;
        this.workOrders = workOrders;
        this.clock = clock;
    }

    public List<UserTask> listTasks(String assignee, String status) {
        return tasks.findAll().stream()
                .filter(value -> blank(assignee) || contains(value.assignee(), assignee) || contains(value.team(), assignee))
                .filter(value -> blank(status) || value.status().equalsIgnoreCase(status.trim()))
                .sorted(Comparator.comparing(UserTask::completedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(UserTask::dueAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(UserTask::createdAt).reversed())
                .toList();
    }

    public UserTask createTask(String taskType, String sourceType, String sourceId, String title,
                               String description, String assetCode, String assignee, String team,
                               String priority, Instant dueAt) {
        try {
            Instant now = clock.instant();
            return tasks.save(UserTask.create("TASK-" + DAY.format(now) + "-" + shortId(), taskType,
                    sourceType, sourceId, title, description, assetCode, assignee, team, priority, dueAt, now));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public UserTask updateTask(String taskNo, String title, String description, String assetCode,
                               String assignee, String team, String priority, Instant dueAt, String status) {
        try {
            UserTask current = getTask(taskNo);
            return tasks.save(current.revise(title, description, assetCode, assignee, team,
                    priority, dueAt, status, clock.instant()));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public UserTask completeTask(String taskNo, String operator) {
        return tasks.save(getTask(taskNo).complete(operator, clock.instant()));
    }

    public List<ShiftHandover> listHandovers(String status) {
        return handovers.findAll().stream()
                .filter(value -> blank(status) || value.status().equalsIgnoreCase(status.trim()))
                .sorted(Comparator.comparing(ShiftHandover::shiftDate).thenComparing(ShiftHandover::createdAt).reversed())
                .toList();
    }

    public ShiftHandover createHandover(LocalDate shiftDate, String outgoingShift, String incomingShift,
                                        String outgoingLeader, String incomingLeader, String summary,
                                        List<String> riskItems, List<String> unfinishedItems,
                                        List<String> equipmentExceptions, String notes) {
        try {
            Instant now = clock.instant();
            return handovers.save(ShiftHandover.create("HO-" + DAY.format(now) + "-" + shortId(),
                    shiftDate, outgoingShift, incomingShift, outgoingLeader, incomingLeader, summary,
                    riskItems, unfinishedItems, equipmentExceptions, notes, now));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public ShiftHandover generateHandover(String outgoingShift, String incomingShift,
                                          String outgoingLeader, String incomingLeader) {
        var openAlarms = alarms.findAll(new PageQuery(0, 200)).content().stream()
                .filter(value -> value.status() != AlarmStatus.CLOSED && value.status() != AlarmStatus.INVALID
                        && value.status() != AlarmStatus.SUPPRESSED).toList();
        var activeOrders = workOrders.findAll(new PageQuery(0, 200)).content().stream()
                .filter(value -> value.status() != WorkOrderStatus.CLOSED && value.status() != WorkOrderStatus.CANCELLED)
                .toList();
        List<String> risks = openAlarms.stream().limit(20)
                .map(value -> value.deviceCode() + " " + value.level() + "：" + value.summary()).toList();
        List<String> unfinished = activeOrders.stream().limit(20)
                .map(value -> value.orderNo() + " " + value.title() + "（" + value.status().label() + "）").toList();
        List<String> exceptions = openAlarms.stream()
                .filter(value -> value.levelClass().equals("severe") || value.levelClass().equals("limited"))
                .map(value -> value.deviceCode() + " / " + value.component() + " / " + value.summary()).toList();
        String summary = "自动汇总" + openAlarms.size() + "条活动告警、" + activeOrders.size()
                + "张未闭环工单；请交接双方逐项核对并签认。";
        return createHandover(LocalDate.now(), outgoingShift, incomingShift, outgoingLeader,
                incomingLeader, summary, risks, unfinished, exceptions,
                "由规则汇总生成草稿，提交前允许人工补充；不替代现场口头交接。 ");
    }

    public ShiftHandover updateHandover(String no, LocalDate shiftDate, String outgoingShift,
                                        String incomingShift, String outgoingLeader, String incomingLeader,
                                        String summary, List<String> riskItems, List<String> unfinishedItems,
                                        List<String> equipmentExceptions, String notes) {
        try {
            return handovers.save(getHandover(no).revise(shiftDate, outgoingShift, incomingShift,
                    outgoingLeader, incomingLeader, summary, riskItems, unfinishedItems,
                    equipmentExceptions, notes, clock.instant()));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public ShiftHandover submitHandover(String no, String operator) {
        return handovers.save(getHandover(no).submit(operator, clock.instant()));
    }

    public ShiftHandover receiveHandover(String no, String operator) {
        return handovers.save(getHandover(no).receive(operator, clock.instant()));
    }

    private UserTask getTask(String no) {
        return tasks.findByTaskNo(no.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("未找到待办：" + no));
    }
    private ShiftHandover getHandover(String no) {
        return handovers.findByHandoverNo(no.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("未找到交接记录：" + no));
    }
    private String shortId() { return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword.trim().toLowerCase(Locale.ROOT));
    }
}
