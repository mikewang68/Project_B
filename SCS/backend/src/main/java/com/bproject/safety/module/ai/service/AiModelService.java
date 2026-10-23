package com.bproject.safety.module.ai.service;

import com.bproject.safety.common.error.ApiException;
import com.bproject.safety.common.realtime.DomainLivePublisher;
import com.bproject.safety.module.ai.dto.AiModelDtos.AiInferenceRequest;
import com.bproject.safety.module.ai.dto.AiModelDtos.AiInferenceResult;
import com.bproject.safety.module.ai.dto.AiModelDtos.DetectedObjectBox;
import com.bproject.safety.module.ai.dto.AiModelDtos.EscalateCaseRequest;
import com.bproject.safety.module.ai.dto.AiModelDtos.EscalateCaseResult;
import com.bproject.safety.module.ai.dto.AiModelDtos.UpdateModelConfigRequest;
import com.bproject.safety.module.ai.model.AiModelInfo;
import com.bproject.safety.module.ai.repository.AiModelRepository;
import com.bproject.safety.module.alert.model.AlertEvidence;
import com.bproject.safety.module.alert.model.DecisionSources;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.module.alert.service.AlertService.NewRiskAlert;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * AI 模型选用与实际识别案例推理服务。
 */
@Service
public class AiModelService {

    private final AiModelRepository modelRepository;
    private final AlertService alertService;
    private final DomainLivePublisher livePublisher;
    private final Clock clock;
    /** review-#5：热切换多模型 save 事务化后的广播门控。 */
    private final com.bproject.safety.common.realtime.LiveEventGate gate;

    /** 兼容历史测试直连（无广播门控，行为与旧版一致）。 */
    public AiModelService(AiModelRepository modelRepository,
                          AlertService alertService,
                          DomainLivePublisher livePublisher,
                          Clock clock) {
        this(modelRepository, alertService, livePublisher, clock, null);
    }

    @Autowired
    public AiModelService(AiModelRepository modelRepository,
                          AlertService alertService,
                          DomainLivePublisher livePublisher, Clock clock,
                          @org.springframework.lang.Nullable com.bproject.safety.common.realtime.LiveEventGate gate) {
        this.modelRepository = modelRepository;
        this.alertService = alertService;
        this.livePublisher = livePublisher;
        this.clock = clock;
        this.gate = gate;
    }

    /** 广播统一收口：有事务时延迟至 afterCommit。 */
    private void broadcast(String type, java.util.Map<String, Object> data) {
        if (gate != null) {
            gate.buffer(() -> livePublisher.publish(type, data));
        } else {
            livePublisher.publish(type, data);
        }
    }

    /** 查询所有 AI 模型 */
    public List<AiModelInfo> listModels() {
        return modelRepository.findAll();
    }

    /** 获取单个模型详情 */
    public AiModelInfo getModel(String id) {
        return modelRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("AI 模型不存在: " + id));
    }

    /** 选用指定模型为生效模型。review-#5：循环多模型 save 纳入事务，中途失败不再出现双 ACTIVE。 */
    @org.springframework.transaction.annotation.Transactional
    public AiModelInfo selectModel(String modelId) {
        AiModelInfo target = getModel(modelId);
        List<AiModelInfo> all = modelRepository.findAll();
        for (AiModelInfo m : all) {
            if (m.id.equals(modelId)) {
                m.isSelected = true;
                m.status = "ACTIVE";
                modelRepository.save(m);
            } else if (m.isSelected) {
                m.isSelected = false;
                m.status = "READY";
                modelRepository.save(m);
            }
        }
        AiModelInfo updated = getModel(modelId);
        broadcast("ai.model_selected", Map.of(
                "modelId", updated.id,
                "modelName", updated.name,
                "taskType", updated.taskType,
                "engine", updated.engine,
                "confidenceThreshold", updated.confidenceThreshold
        ));
        return updated;
    }

    /** 更新模型配置（阈值、引擎等） */
    public AiModelInfo updateModelConfig(String modelId, UpdateModelConfigRequest req) {
        AiModelInfo model = getModel(modelId);
        if (req.confidenceThreshold() != null) {
            double c = req.confidenceThreshold();
            if (c < 0.20 || c > 0.99) {
                throw ApiException.unprocessable("置信度阈值必须在 0.20 ~ 0.99 范围内");
            }
            model.confidenceThreshold = Math.round(c * 100.0) / 100.0;
        }
        if (req.iouThreshold() != null) {
            double iou = req.iouThreshold();
            if (iou < 0.10 || iou > 0.90) {
                throw ApiException.unprocessable("IoU 阈值必须在 0.10 ~ 0.90 范围内");
            }
            model.iouThreshold = Math.round(iou * 100.0) / 100.0;
        }
        if (req.engine() != null && !req.engine().isBlank()) {
            model.engine = req.engine().trim();
        }
        if (req.status() != null && !req.status().isBlank()) {
            model.status = req.status().trim();
        }
        if (Boolean.TRUE.equals(req.isSelected())) {
            return selectModel(modelId);
        }
        return modelRepository.save(model);
    }

    /**
     * 执行实际识别案例推理：
     * 根据场景（PPE、禁区入侵、火灾烟雾、跌倒、吊钩偏摆）与当前选用模型参数，执行推理检测，输出结构化目标框与安全诊断结论。
     */
    public AiInferenceResult runInference(AiInferenceRequest req) {
        AiModelInfo model;
        if (req.modelId() != null && !req.modelId().isBlank()) {
            model = getModel(req.modelId());
        } else {
            model = modelRepository.findSelected().orElseGet(() -> {
                List<AiModelInfo> list = modelRepository.findAll();
                return list.isEmpty() ? null : list.get(0);
            });
        }
        if (model == null) {
            throw ApiException.unprocessable("当前无可用 AI 检测模型");
        }

        double threshold = (req.confidenceThreshold() != null && req.confidenceThreshold() > 0)
                ? req.confidenceThreshold()
                : model.confidenceThreshold;

        String scenario = req.scenario() == null ? "ppe_violation" : req.scenario().toLowerCase();
        double simulatedLatency = Math.round((model.latencyMs + ThreadLocalRandom.current().nextDouble(-3.5, 4.5)) * 10.0) / 10.0;
        simulatedLatency = Math.max(18.0, simulatedLatency);

        List<DetectedObjectBox> rawBoxes = generateScenarioDetections(scenario);
        // 按置信度阈值过滤目标框
        List<DetectedObjectBox> detectedObjects = rawBoxes.stream()
                .filter(b -> b.confidence() >= threshold)
                .toList();

        long violations = detectedObjects.stream().filter(DetectedObjectBox::isViolation).count();
        String riskLevel = violations >= 2 ? RiskLevels.SEVERE : (violations == 1 ? RiskLevels.WARNING : RiskLevels.NORMAL);
        String scenarioName = resolveScenarioName(scenario);

        String summary = generateSummary(scenario, violations, detectedObjects);
        String suggestion = generateSuggestion(scenario, violations);

        String timestamp = OffsetDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String inferenceId = "INF-" + OffsetDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        return new AiInferenceResult(
                inferenceId,
                model.id,
                model.name,
                model.version,
                model.engine,
                simulatedLatency,
                scenario,
                scenarioName,
                req.imageSource() != null ? req.imageSource() : resolveDefaultImage(scenario),
                detectedObjects,
                (int) violations,
                riskLevel,
                summary,
                suggestion,
                timestamp
        );
    }

    /** 一键将实际识别案例上报并沉淀为系统正式安全告警 */
    public EscalateCaseResult escalateCase(EscalateCaseRequest req) {
        if (req == null) {
            throw ApiException.unprocessable("告警上报请求不能为空");
        }
        String riskCode = RiskLevels.normalize(req.riskLevel() != null ? req.riskLevel() : "SEVERE");
        String area = req.area() != null && !req.area().isBlank() ? req.area() : "装卸区 A";
        String title = "AI视觉复核: " + (req.summary() != null ? req.summary() : "检测到违规作业行为");
        String eventType = resolveEventType(req.scenario());

        // 构造 AI 违规检测框证据
        List<AlertEvidence.DetectionBox> evidenceBoxes = new ArrayList<>();
        if (req.objects() != null) {
            int idx = 1;
            for (DetectedObjectBox obj : req.objects()) {
                String tone = obj.isViolation() ? ("SEVERE".equalsIgnoreCase(obj.riskLevel()) ? "danger" : "warning") : "normal";
                evidenceBoxes.add(new AlertEvidence.DetectionBox(
                        "box-" + (idx++),
                        obj.labelZh(),
                        obj.confidence(),
                        (int) Math.round(obj.x()),
                        (int) Math.round(obj.y()),
                        (int) Math.round(obj.width()),
                        (int) Math.round(obj.height()),
                        tone
                ));
            }
        }
        String timeStr = OffsetDateTime.now(clock).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        AlertEvidence evidence = AlertEvidence.AiEvidence.of(
                req.imageSource() != null ? req.imageSource() : "/demo-evidence/worker-no-helmet-vest.jpg",
                evidenceBoxes,
                0.95,
                req.modelName() != null ? req.modelName() : "YOLOv8-SafetyHelmet-v2.3",
                area + "高清球机 CAM-01",
                timeStr
        );

        String dedupKey = "AI-CASE-ESCALATE:" + (req.inferenceId() != null ? req.inferenceId() : UUID.randomUUID().toString());

        DemoAlert alert = alertService.createRiskAlert(new NewRiskAlert(
                "AI违规",
                dedupKey,
                title,
                eventType,
                riskCode,
                area,
                "作业班组现场人员",
                "RULE-AI-PPE-01",
                "v2.1",
                DecisionSources.AI_MODEL,
                req.modelId() != null ? req.modelId() : "MODEL-YOLO-PPE-01",
                req.modelName() != null ? req.modelName() : "YOLOv8-SafetyHelmet-v2.3",
                0,
                evidence,
                "现场 AI 视觉模型实时检测推理命中违规目标",
                "识别案例经人工确认有效，一键上报为安全管控中心正式告警"
        ));

        return new EscalateCaseResult(
                true,
                alert.id,
                alert.getRisk(),
                alert.getStatus(),
                "已成功生成安全告警 " + alert.id + "，已联动广播至现场广播大屏与安全监管台"
        );
    }

    // ---------- 内部检测案例生成逻辑 ----------

    private List<DetectedObjectBox> generateScenarioDetections(String scenario) {
        List<DetectedObjectBox> boxes = new ArrayList<>();
        switch (scenario) {
            case "ppe_violation" -> {
                // 案例 1：作业人员劳保穿戴违规（未戴安全帽、未穿反光衣）
                boxes.add(new DetectedObjectBox(
                        "no_helmet", "未戴安全帽", 0.96, true, "SEVERE",
                        28.5, 21.0, 14.0, 16.5, "违规：头部裸露未佩戴标准黄色安全帽"
                ));
                boxes.add(new DetectedObjectBox(
                        "no_vest", "未穿反光衣", 0.92, true, "WARNING",
                        25.0, 37.5, 22.0, 38.0, "违规：上身未穿戴荧光反光防护背心"
                ));
                boxes.add(new DetectedObjectBox(
                        "person", "作业人员(合规)", 0.98, false, "NORMAL",
                        64.0, 24.0, 20.0, 56.0, "正常：佩戴白色安全帽，穿戴橙色反光衣"
                ));
                boxes.add(new DetectedObjectBox(
                        "helmet", "安全帽(合规)", 0.97, false, "NORMAL",
                        67.0, 24.5, 12.0, 13.0, "合规：标准防砸安全帽"
                ));
            }
            case "danger_intrusion" -> {
                // 案例 2：龙门吊重载禁区越界入侵
                boxes.add(new DetectedObjectBox(
                        "person_intrude", "人员越界入侵", 0.95, true, "SEVERE",
                        42.0, 36.0, 18.0, 48.0, "高危：人员已踏入龙门吊 2 号主轨道 1.5 米回转禁区"
                ));
                boxes.add(new DetectedObjectBox(
                        "danger_boundary", "电子红线边界", 0.99, true, "WARNING",
                        35.0, 32.0, 38.0, 56.0, "警戒：物理隔离带与光栅警戒区域"
                ));
            }
            case "fire_smoke" -> {
                // 案例 3：堆料区初起火灾烟雾
                boxes.add(new DetectedObjectBox(
                        "smoke", "可疑浓烟", 0.93, true, "WARNING",
                        52.0, 18.0, 26.0, 32.0, "环境异常：料仓 B2 区域聚集灰白色浓烟"
                ));
                boxes.add(new DetectedObjectBox(
                        "fire", "初起火苗", 0.89, true, "SEVERE",
                        58.0, 36.0, 16.0, 18.0, "火险预警：监测到微小明火热点"
                ));
            }
            case "fall_detected" -> {
                // 案例 4：人员摔倒与异常姿态
                boxes.add(new DetectedObjectBox(
                        "fall_down", "人员摔倒卧地", 0.94, true, "SEVERE",
                        33.0, 52.0, 34.0, 26.0, "突发意外：人体姿态倾角大于 75 度且静止超 10 秒"
                ));
            }
            case "crane_sway" -> {
                // 案例 5：吊钩剧烈偏摆防撞
                boxes.add(new DetectedObjectBox(
                        "hook_sway_severe", "吊具偏摆过大", 0.96, true, "SEVERE",
                        45.0, 22.0, 22.0, 44.0, "特种设备风险：起吊集装箱偏摆角达到 8.5°，逼近安全临界值"
                ));
            }
            default -> {
                boxes.add(new DetectedObjectBox(
                        "no_helmet", "未戴安全帽", 0.95, true, "SEVERE",
                        30.0, 25.0, 16.0, 18.0, "检测到未规范佩戴劳保安全帽"
                ));
            }
        }
        return boxes;
    }

    private String resolveScenarioName(String scenario) {
        return switch (scenario) {
            case "ppe_violation" -> "装卸区作业人员未穿戴劳保用品(安全帽/反光衣)";
            case "danger_intrusion" -> "龙门吊轨道重载回转禁区人员入侵";
            case "fire_smoke" -> "露天堆料区明火与可疑烟雾识别";
            case "fall_detected" -> "装卸高台通道作业人员打滑跌倒";
            case "crane_sway" -> "门式起重机吊钩集装箱剧烈偏摆防碰撞";
            default -> "工业现场安全视觉目标检测";
        };
    }

    private String resolveEventType(String scenario) {
        return switch (scenario) {
            case "ppe_violation" -> "未佩戴安全防护装备";
            case "danger_intrusion" -> "危险区域人员违规闯入";
            case "fire_smoke" -> "堆场明火烟雾异常";
            case "fall_detected" -> "人员摔倒与异常姿态";
            case "crane_sway" -> "特种起重设备防碰撞超限";
            default -> "AI视觉检测异常";
        };
    }

    private String resolveDefaultImage(String scenario) {
        return switch (scenario) {
            case "danger_intrusion" -> "/demo-evidence/crane-track-intrusion.jpg";
            case "fire_smoke" -> "/demo-evidence/warehouse-smoke.jpg";
            case "fall_detected" -> "/demo-evidence/worker-fall.jpg";
            case "crane_sway" -> "/demo-evidence/crane-hook-sway.jpg";
            default -> "/demo-evidence/worker-no-helmet-vest.jpg";
        };
    }

    private String generateSummary(String scenario, long violations, List<DetectedObjectBox> objects) {
        if (violations == 0) {
            return "全视场分析完毕：所有检测目标均合规，未发现违规违章行为。";
        }
        return switch (scenario) {
            case "ppe_violation" -> "检测到 " + violations + " 项劳保穿戴严重违规：作业人员未戴安全帽 (置信度 96%)、未穿反光衣 (置信度 92%)。";
            case "danger_intrusion" -> "严重违规：检测到 1 名作业人员擅自翻越物理警示线进入重载机械回转盲区 (置信度 95%)。";
            case "fire_smoke" -> "安全火情预警：检测到堆料仓上空出现微小明火与可疑烟雾团块 (置信度 93%)。";
            case "fall_detected" -> "突发人员险情：检测到高台通道有人员跌倒卧地不起 (置信度 94%)，可能发生身体伤害。";
            case "crane_sway" -> "设备安全告警：龙门吊吊钩吊具横向偏摆角度超限 (置信度 96%)，存在撞击货架风险。";
            default -> "AI 视觉检测发现 " + violations + " 处违规异常目标，需人工核对。";
        };
    }

    private String generateSuggestion(String scenario, long violations) {
        if (violations == 0) {
            return "继续保持作业规范监测，建议维持默认巡检频率。";
        }
        return switch (scenario) {
            case "ppe_violation" -> "建议通过现场广播喊话或手环震动提醒作业人员立即整改，佩戴安全帽并穿戴反光背心后方可施工作业。";
            case "danger_intrusion" -> "建议立即触发机械联锁声光报警并通知起重司机暂停小车动作，指派安全员前往阻截疏散。";
            case "fire_smoke" -> "建议立即通知微型消防站与现场班组长携带灭火器赶往现场排查，防止火情蔓延。";
            case "fall_detected" -> "建议立即安排就近医务/巡查人员前往查看伤情，手环一键呼叫对讲。";
            case "crane_sway" -> "建议机械系统切入微动防摇模式，限制小车加减速，避免碰撞地面设施。";
            default -> "建议核实违规真实性，按标准流程生成安全处置单。";
        };
    }
}
