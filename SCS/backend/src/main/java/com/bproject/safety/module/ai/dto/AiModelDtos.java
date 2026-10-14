package com.bproject.safety.module.ai.dto;

import java.util.List;

/**
 * AI 模型管理与实际案例识别相关 DTO。
 */
public final class AiModelDtos {

    private AiModelDtos() {}

    /** 更新模型配置请求 */
    public record UpdateModelConfigRequest(
            Double confidenceThreshold,
            Double iouThreshold,
            String engine,
            Boolean isSelected,
            String status
    ) {}

    /** 选用模型请求 */
    public record SelectModelRequest(
            String modelId
    ) {}

    /** 实际识别案例推理请求 */
    public record AiInferenceRequest(
            String modelId,
            String scenario,
            String caseId,
            String imageSource,
            Double confidenceThreshold
    ) {}

    /** 识别出的目标目标框与标签 */
    public record DetectedObjectBox(
            String label,
            String labelZh,
            double confidence,
            boolean isViolation,
            String riskLevel,
            double x,
            double y,
            double width,
            double height,
            String note
    ) {}

    /** 实际识别推理输出结果 */
    public record AiInferenceResult(
            String inferenceId,
            String modelId,
            String modelName,
            String modelVersion,
            String engineUsed,
            double latencyMs,
            String scenario,
            String scenarioName,
            String imageSource,
            List<DetectedObjectBox> detectedObjects,
            int violationCount,
            String riskLevel,
            String summary,
            String suggestion,
            String timestamp
    ) {}

    /** 将识别案例一键上报为安全告警请求 */
    public record EscalateCaseRequest(
            String inferenceId,
            String modelId,
            String modelName,
            String scenario,
            String area,
            String riskLevel,
            String summary,
            String imageSource,
            List<DetectedObjectBox> objects
    ) {}

    /** 告警上报结果 */
    public record EscalateCaseResult(
            boolean success,
            String alertId,
            String riskLevel,
            String status,
            String message
    ) {}
}
