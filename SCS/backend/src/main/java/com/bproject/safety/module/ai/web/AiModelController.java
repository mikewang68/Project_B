package com.bproject.safety.module.ai.web;

import com.bproject.safety.module.ai.dto.AiModelDtos.AiInferenceRequest;
import com.bproject.safety.module.ai.dto.AiModelDtos.AiInferenceResult;
import com.bproject.safety.module.ai.dto.AiModelDtos.EscalateCaseRequest;
import com.bproject.safety.module.ai.dto.AiModelDtos.EscalateCaseResult;
import com.bproject.safety.module.ai.dto.AiModelDtos.UpdateModelConfigRequest;
import com.bproject.safety.module.ai.model.AiModelInfo;
import com.bproject.safety.module.ai.service.AiModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 模型管理与实际案例识别控制器。
 * 成功响应直接返回业务体（不包装 code/data），错误走全局统一错误结构。
 */
@RestController
@RequestMapping("/api/v1/ai/models")
@Tag(name = "AI模型管理", description = "AI 模型选用、参数调优与实际识别案例推理")
public class AiModelController {

    private final AiModelService modelService;

    @Autowired
    public AiModelController(AiModelService modelService) {
        this.modelService = modelService;
    }

    /** 模型列表 */
    @GetMapping
    @Operation(summary = "查询所有 AI 模型（含当前选用标记）")
    public List<AiModelInfo> list() {
        return modelService.listModels();
    }

    /** 单个模型详情 */
    @GetMapping("/{id}")
    @Operation(summary = "查询单个模型详情")
    public AiModelInfo get(@PathVariable String id) {
        return modelService.getModel(id);
    }

    /** 选用模型为激活状态 */
    @PostMapping("/{id}/select")
    @Operation(summary = "选用模型为生效模型")
    public AiModelInfo select(@PathVariable String id) {
        return modelService.selectModel(id);
    }

    /** 更新模型参数（置信度阈值、IoU、推理引擎等） */
    @PutMapping("/{id}/config")
    @Operation(summary = "更新模型参数与阈值")
    public AiModelInfo updateConfig(
            @PathVariable String id,
            @RequestBody UpdateModelConfigRequest req) {
        return modelService.updateModelConfig(id, req);
    }

    /** 执行实际识别案例推理 */
    @PostMapping("/infer")
    @Operation(summary = "执行实际识别案例推理")
    public AiInferenceResult infer(@RequestBody(required = false) AiInferenceRequest req) {
        AiInferenceRequest safeReq = req != null ? req : new AiInferenceRequest(null, "ppe_violation", null, null, null);
        return modelService.runInference(safeReq);
    }

    /** 一键将识别案例上报为系统正式安全告警 */
    @PostMapping("/escalate")
    @Operation(summary = "识别案例一键生成系统告警")
    public EscalateCaseResult escalate(@RequestBody EscalateCaseRequest req) {
        return modelService.escalateCase(req);
    }
}

