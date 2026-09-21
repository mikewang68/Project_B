package com.bproject.safety.module.alert.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 告警模块写接口请求体（全部字段可空，由 Service 做默认值兜底，兼容前端联调前后两种调用方式）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class AlertRequests {

    private AlertRequests() {
    }

    /** 确认事件：operator 缺省时由 Service 取当前 Demo 用户。 */
    public record ConfirmRequest(String operator) {
    }

    /** 派单；同时兼容 assigneeId/assigneeName/deadline 命名（Service 内归并）。 */
    public record AssignRequest(String assignee, String assigneeId, String assigneeName,
                                String priority, Integer limitMin, Integer slaLimitMin,
                                String deadline, String note) {
    }

    /** 接单 / 开始处理。 */
    public record StartRequest(String handler, String operator) {
    }

    /** 提交处置结果；兼容 riskResolved/evidence 命名。 */
    public record TreatmentRequest(List<String> measures, String result, String attachment,
                                   String note, Boolean riskResolved, String evidence) {
    }

    /** 复核通过并关闭。 */
    public record ReviewRequest(String reviewer, String operator, String note, String reviewNote) {
    }

    /** 人工接管（PLC 失败兜底，只记录业务，不连真实 PLC）。 */
    public record TakeoverRequest(String operator, String reason, String note, String result) {
    }

    /** 转派；assigneeId 为用户 code（USR-xxx，权威），assignee 为旧版姓名兼容字段。 */
    public record TransferRequest(String assignee, String assigneeId, String note, String operator) {
    }

    /** 复核驳回，继续处理。 */
    public record ReviewRejectRequest(String reason, String operator) {
    }

    /** 事件升级。 */
    public record EscalateRequest(String reason, String level, List<String> targets, String operator) {
    }

    /** 发起联动演示：mode=success|fail。 */
    public record LinkageRequest(String mode) {
    }
}
