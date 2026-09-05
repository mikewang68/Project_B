package com.bproject.safety.module.ai.dto;

/** AI 模块写操作请求体（全部允许为空，Demo 用户兜底）。 */
public final class AiRequests {

    private AiRequests() {
    }

    /** 确认违规。 */
    public record ReviewRequest(String reviewer) {
    }

    /** 标记误报。 */
    public record FalsePositiveRequest(String reason, String reviewer, String note) {
    }

    /** 暂不确定。 */
    public record UncertainRequest(String reviewer) {
    }

    /** AI 派单（无关联 Alert 时先创建 Alert 再复用 Alert 派单主链）。 */
    public record AiAssignRequest(String assignee, String priority, String note, String reviewer) {
    }

    /** 开始处理 / 关闭。 */
    public record ProcessRequest(String operator, String note) {
    }

    /**
     * 模拟事件：kind=new（默认，新 AI 事件）/ low-confidence（低置信度翻越护栏）/ camera-fault（摄像头异常）。
     */
    public record SimulateRequest(String kind) {
    }
}
