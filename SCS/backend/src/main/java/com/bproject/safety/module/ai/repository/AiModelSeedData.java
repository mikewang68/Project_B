package com.bproject.safety.module.ai.repository;

import com.bproject.safety.module.ai.model.AiModelInfo;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * AI 工业模型预置种子（任务书：预置 5 款工业级模型支持在线选用与热切换）。
 *
 * <p>InMemory 版构造灌种与 Jdbc 版空表自动补种共用本清单，保证两种 profile
 * 行为对等；预置内容变更只改这一处。</p>
 */
final class AiModelSeedData {

    private AiModelSeedData() {
    }

    static List<AiModelInfo> presets(OffsetDateTime now) {
        AiModelInfo m1 = new AiModelInfo();
        m1.id = "MODEL-YOLO-PPE-01";
        m1.name = "YOLOv8-SafetyHelmet-v2.3";
        m1.taskType = "人员防护穿戴检测(PPE)";
        m1.version = "v2.3.1";
        m1.engine = "TensorRT FP16";
        m1.accuracyMap = 0.946;
        m1.latencyMs = 38.5;
        m1.fps = 26.0;
        m1.confidenceThreshold = 0.70;
        m1.iouThreshold = 0.45;
        m1.isSelected = true;
        m1.status = "ACTIVE";
        m1.weightSize = "43.5 MB";
        m1.supportedClasses = List.of("helmet", "no_helmet", "vest", "no_vest");
        m1.description = "针对工业站场高精度人员劳保用品（安全帽、反光背心）穿戴合规性检测，具备小目标远距离识别强化";
        m1.updatedAt = now;

        AiModelInfo m2 = new AiModelInfo();
        m2.id = "MODEL-YOLO-DANGER-02";
        m2.name = "YOLOv11-DangerZone-v1.2";
        m2.taskType = "危险区域入侵检测";
        m2.version = "v1.2.0";
        m2.engine = "ONNX Runtime";
        m2.accuracyMap = 0.962;
        m2.latencyMs = 42.0;
        m2.fps = 23.8;
        m2.confidenceThreshold = 0.75;
        m2.iouThreshold = 0.45;
        m2.isSelected = false;
        m2.status = "READY";
        m2.weightSize = "52.8 MB";
        m2.supportedClasses = List.of("person_intrude", "vehicle_intrude", "danger_boundary");
        m2.description = "结合物理电子围栏与边缘摄像头视角，实时检测人员或非授权移动载具越界进入回转半径或重载作业区";
        m2.updatedAt = now;

        AiModelInfo m3 = new AiModelInfo();
        m3.id = "MODEL-DETR-FIRE-03";
        m3.name = "RT-DETR-FlameSmoke-v3.0";
        m3.taskType = "作业烟火识别";
        m3.version = "v3.0.2";
        m3.engine = "TensorRT FP16";
        m3.accuracyMap = 0.928;
        m3.latencyMs = 54.0;
        m3.fps = 18.5;
        m3.confidenceThreshold = 0.65;
        m3.iouThreshold = 0.50;
        m3.isSelected = false;
        m3.status = "READY";
        m3.weightSize = "78.4 MB";
        m3.supportedClasses = List.of("smoke", "fire", "spark");
        m3.description = "基于 Transformer 端到端目标检测，过滤粉尘干扰，精准捕捉堆场/料区可疑明火烟雾与焊花飞溅隐患";
        m3.updatedAt = now;

        AiModelInfo m4 = new AiModelInfo();
        m4.id = "MODEL-POSE-FALL-04";
        m4.name = "FastPose-FallDetection-v2.0";
        m4.taskType = "人员摔倒姿态识别";
        m4.version = "v2.0.0";
        m4.engine = "Ascend CANN";
        m4.accuracyMap = 0.915;
        m4.latencyMs = 45.0;
        m4.fps = 22.2;
        m4.confidenceThreshold = 0.70;
        m4.iouThreshold = 0.40;
        m4.isSelected = false;
        m4.status = "READY";
        m4.weightSize = "36.2 MB";
        m4.supportedClasses = List.of("fall_down", "lying_still", "climbing_hazard");
        m4.description = "针对高处作业坠落、打滑摔倒及长时间卧地不起进行骨骼关键点追踪，联动人员手环与救援处置";
        m4.updatedAt = now;

        AiModelInfo m5 = new AiModelInfo();
        m5.id = "MODEL-CRANE-SWAY-05";
        m5.name = "CraneSway-Vision-v1.5";
        m5.taskType = "吊钩偏摆防撞";
        m5.version = "v1.5.0";
        m5.engine = "ONNX Runtime";
        m5.accuracyMap = 0.951;
        m5.latencyMs = 35.0;
        m5.fps = 28.5;
        m5.confidenceThreshold = 0.72;
        m5.iouThreshold = 0.45;
        m5.isSelected = false;
        m5.status = "READY";
        m5.weightSize = "29.7 MB";
        m5.supportedClasses = List.of("hook_sway_severe", "blind_spot_hazard", "cable_twist");
        m5.description = "集成龙门吊高清小车球机，实时解算起升重物偏摆角与地面人员/设备安全间距，防撞超前预警";
        m5.updatedAt = now;

        return List.of(m1, m2, m3, m4, m5);
    }
}
