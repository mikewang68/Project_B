package com.bproject.safety.module.ai.repository;

import com.bproject.safety.module.ai.model.AiModelInfo;
import com.bproject.safety.support.demo.DemoClearableStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * 进程内 AI 模型存储与初始化种子仓库（遵守软删除与 copy-on-read/write 纯洁契约）。
 */
@Repository
public class InMemoryAiModelRepository implements AiModelRepository, DemoClearableStore {

    private final ConcurrentHashMap<String, AiModelInfo> store = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public InMemoryAiModelRepository(Clock clock) {
        this.clock = clock;
        initDefaultSeeds();
    }

    public InMemoryAiModelRepository() {
        this(Clock.systemDefaultZone());
    }

    private void initDefaultSeeds() {
        OffsetDateTime now = OffsetDateTime.now(clock);

        // 1. 安全帽与防护穿戴模型（默认选用生效）
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
        m1.isDeleted = false;
        m1.updatedAt = now;
        store.put(m1.id, m1);

        // 2. 危险区域越界入侵模型
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
        m2.isDeleted = false;
        m2.updatedAt = now;
        store.put(m2.id, m2);

        // 3. 作业区明火烟雾环境感知模型
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
        m3.isDeleted = false;
        m3.updatedAt = now;
        store.put(m3.id, m3);

        // 4. 人员异常跌倒与姿态检测模型
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
        m4.isDeleted = false;
        m4.updatedAt = now;
        store.put(m4.id, m4);

        // 5. 特种设备吊钩偏摆防撞模型
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
        m5.isDeleted = false;
        m5.updatedAt = now;
        store.put(m5.id, m5);
    }

    @Override
    public List<AiModelInfo> findAll() {
        return store.values().stream()
                .filter(m -> !m.isDeleted)
                .sorted(Comparator.comparing((AiModelInfo m) -> !m.isSelected)
                        .thenComparing(m -> m.id))
                .map(AiModelInfo::copy)
                .toList();
    }

    @Override
    public Optional<AiModelInfo> findById(String id) {
        AiModelInfo m = store.get(id);
        if (m == null || m.isDeleted) {
            return Optional.empty();
        }
        return Optional.of(m.copy());
    }

    @Override
    public Optional<AiModelInfo> findSelected() {
        return store.values().stream()
                .filter(m -> !m.isDeleted && m.isSelected)
                .findFirst()
                .map(AiModelInfo::copy);
    }

    @Override
    public AiModelInfo save(AiModelInfo model) {
        if (model == null || model.id == null) {
            throw new IllegalArgumentException("model and model.id must not be null");
        }
        if (model.updatedAt == null) {
            model.updatedAt = OffsetDateTime.now(clock);
        }
        store.put(model.id, model.copy());
        return model.copy();
    }

    @Override
    public boolean deleteById(String id) {
        AiModelInfo m = store.get(id);
        if (m == null || m.isDeleted) {
            return false;
        }
        m.isDeleted = true;
        m.updatedAt = OffsetDateTime.now(clock);
        return true;
    }

    @Override
    public void clearDemoData() {
        store.clear();
        initDefaultSeeds();
    }
}
