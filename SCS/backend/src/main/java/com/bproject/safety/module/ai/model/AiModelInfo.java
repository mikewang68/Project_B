package com.bproject.safety.module.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 视觉检测模型实体：支持多模型选用、运行状态、置信度阈值调优与推理引擎配置。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiModelInfo {

    /** 模型唯一标识，如 MODEL-YOLO-PPE-01 */
    public String id;

    /** 模型名称，如 YOLOv8-SafetyHelmet-v2.3 */
    public String name;

    /** 业务任务类型：人员防护穿戴检测(PPE) / 危险区域入侵检测 / 作业烟火识别 / 人员跌倒姿态 / 吊钩偏摆防撞 */
    public String taskType;

    /** 模型版本号，如 v2.3.1 */
    public String version;

    /** 推理引擎：ONNX Runtime / TensorRT FP16 / Ascend CANN / PyTorch */
    public String engine;

    /** 检测精度 mAP@0.5，例如 0.946 (94.6%) */
    public double accuracyMap;

    /** 平均推理时延 (ms)，例如 38.5 */
    public double latencyMs;

    /** 推理吞吐 FPS，例如 26.0 */
    public double fps;

    /** 置信度判定阈值（低于该阈值过滤或标记为不确定），范围 0.20 ~ 0.95，默认 0.70 */
    public double confidenceThreshold;

    /** NMS IoU 重叠度阈值，范围 0.20 ~ 0.80，默认 0.45 */
    public double iouThreshold;

    /** 是否为当前任务选用的生效模型 */
    public boolean isSelected;

    /** 运行状态：ACTIVE(运行中) / READY(已就绪) / INACTIVE(停用) */
    public String status;

    /** 权重文件大小，如 43.5 MB */
    public String weightSize;

    /** 模型支持的目标检测类别清单 */
    public List<String> supportedClasses = new ArrayList<>();

    /** 适用场景与技术说明 */
    public String description;

    /** 软删除标记 */
    public boolean isDeleted = false;

    /** 最后更新时间 */
    public OffsetDateTime updatedAt;

    public AiModelInfo copy() {
        AiModelInfo m = new AiModelInfo();
        m.id = this.id;
        m.name = this.name;
        m.taskType = this.taskType;
        m.version = this.version;
        m.engine = this.engine;
        m.accuracyMap = this.accuracyMap;
        m.latencyMs = this.latencyMs;
        m.fps = this.fps;
        m.confidenceThreshold = this.confidenceThreshold;
        m.iouThreshold = this.iouThreshold;
        m.isSelected = this.isSelected;
        m.status = this.status;
        m.weightSize = this.weightSize;
        m.supportedClasses = this.supportedClasses == null ? new ArrayList<>() : new ArrayList<>(this.supportedClasses);
        m.description = this.description;
        m.isDeleted = this.isDeleted;
        m.updatedAt = this.updatedAt;
        return m;
    }
}
