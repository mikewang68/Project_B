package com.bproject.safety.support.phaseb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bproject.safety.common.error.ApiException;
import com.bproject.safety.module.ai.dto.AiModelDtos.AiInferenceRequest;
import com.bproject.safety.module.ai.dto.AiModelDtos.AiInferenceResult;
import com.bproject.safety.module.ai.dto.AiModelDtos.EscalateCaseRequest;
import com.bproject.safety.module.ai.dto.AiModelDtos.EscalateCaseResult;
import com.bproject.safety.module.ai.dto.AiModelDtos.UpdateModelConfigRequest;
import com.bproject.safety.module.ai.model.AiModelInfo;
import com.bproject.safety.module.ai.service.AiModelService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * AI 模型管理选用、阈值调优与实际识别案例推理自动化验证。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiModelManagementAndInferenceTest {

    @Autowired
    private AiModelService aiModelService;

    @Test
    @DisplayName("Test 1: 模型列表查询 - 包含 5 款工业级预置模型，默认选用 PPE 安全帽检测模型")
    void listModelsAndDefaultSelection() {
        List<AiModelInfo> models = aiModelService.listModels();
        assertThat(models).isNotNull().hasSizeGreaterThanOrEqualTo(5);

        AiModelInfo ppeModel = models.stream()
                .filter(m -> "MODEL-YOLO-PPE-01".equals(m.id))
                .findFirst()
                .orElse(null);
        assertThat(ppeModel).isNotNull();
        assertThat(ppeModel.isSelected).isTrue();
        assertThat(ppeModel.status).isEqualTo("ACTIVE");
        assertThat(ppeModel.supportedClasses).contains("helmet", "no_helmet", "vest", "no_vest");
    }

    @Test
    @DisplayName("Test 2: 模型选用切换 - 一键选用危险区域入侵模型，状态同步流转与广播")
    void selectDifferentModel() {
        // 选用 MODEL-YOLO-DANGER-02
        AiModelInfo selected = aiModelService.selectModel("MODEL-YOLO-DANGER-02");
        assertThat(selected.id).isEqualTo("MODEL-YOLO-DANGER-02");
        assertThat(selected.isSelected).isTrue();
        assertThat(selected.status).isEqualTo("ACTIVE");

        // 验证原 PPE 模型变为非选用状态
        AiModelInfo oldModel = aiModelService.getModel("MODEL-YOLO-PPE-01");
        assertThat(oldModel.isSelected).isFalse();
        assertThat(oldModel.status).isEqualTo("READY");

        // 换回 PPE 模型保证基线
        aiModelService.selectModel("MODEL-YOLO-PPE-01");
    }

    @Test
    @DisplayName("Test 3: 模型阈值调整 - 支持置信度与IoU配置调优，卡控越界参数")
    void updateModelThresholds() {
        // 合法调优
        AiModelInfo updated = aiModelService.updateModelConfig("MODEL-YOLO-PPE-01",
                new UpdateModelConfigRequest(0.82, 0.50, "TensorRT FP16", null, null));
        assertThat(updated.confidenceThreshold).isEqualTo(0.82);
        assertThat(updated.iouThreshold).isEqualTo(0.50);

        // 越界值卡控
        assertThatThrownBy(() -> aiModelService.updateModelConfig("MODEL-YOLO-PPE-01",
                new UpdateModelConfigRequest(0.05, null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("置信度阈值必须在 0.20 ~ 0.99 范围内");

        // 恢复默认
        aiModelService.updateModelConfig("MODEL-YOLO-PPE-01",
                new UpdateModelConfigRequest(0.70, 0.45, "TensorRT FP16", null, null));
    }

    @Test
    @DisplayName("Test 4: 实际识别案例推理 - 针对作业人员防护穿戴工况，准确检出未戴安全帽与未穿反光衣")
    void runPracticalRecognitionInference() {
        AiInferenceRequest req = new AiInferenceRequest(
                "MODEL-YOLO-PPE-01",
                "ppe_violation",
                "CASE-PPE-01",
                "/demo-evidence/worker-no-helmet-vest.jpg",
                0.70
        );

        AiInferenceResult result = aiModelService.runInference(req);
        assertThat(result).isNotNull();
        assertThat(result.modelId()).isEqualTo("MODEL-YOLO-PPE-01");
        assertThat(result.scenario()).isEqualTo("ppe_violation");
        assertThat(result.latencyMs()).isGreaterThan(0.0);
        assertThat(result.detectedObjects()).isNotEmpty();

        // 检出违规目标项
        boolean hasNoHelmet = result.detectedObjects().stream().anyMatch(o -> "no_helmet".equals(o.label()));
        boolean hasNoVest = result.detectedObjects().stream().anyMatch(o -> "no_vest".equals(o.label()));
        assertThat(hasNoHelmet).isTrue();
        assertThat(hasNoVest).isTrue();
        assertThat(result.violationCount()).isGreaterThanOrEqualTo(2);
        assertThat(result.riskLevel()).isEqualTo("SEVERE");
        assertThat(result.summary()).contains("未戴安全帽");
        assertThat(result.suggestion()).isNotBlank();
    }

    @Test
    @DisplayName("Test 5: 业务闭环 - 将实际识别案例一键上报为安全管控中心正式告警单")
    void escalateCaseToSystemAlert() {
        EscalateCaseRequest req = new EscalateCaseRequest(
                "INF-TEST-001",
                "MODEL-YOLO-PPE-01",
                "YOLOv8-SafetyHelmet-v2.3",
                "ppe_violation",
                "装卸区 A",
                "SEVERE",
                "作业人员未佩戴安全帽且未穿反光衣",
                "/demo-evidence/worker-no-helmet-vest.jpg",
                List.of()
        );

        EscalateCaseResult res = aiModelService.escalateCase(req);
        assertThat(res.success()).isTrue();
        assertThat(res.alertId()).startsWith("ALM-");
        assertThat(res.status()).isNotBlank();
        assertThat(res.message()).contains("已成功生成安全告警");
    }
}
