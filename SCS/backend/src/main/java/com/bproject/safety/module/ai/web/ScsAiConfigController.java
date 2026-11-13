package com.bproject.safety.module.ai.web;

import com.bproject.safety.module.ai.service.AiModelService;
import com.bproject.safety.module.ai.service.ScsAiConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCS 边缘 AI 服务配置。路径位于 /ai/models 前缀下，复用 SCS 既有路由权限：
 * GET -> safety:module:ai:model:view，PUT -> safety:module:ai:model:edit。
 * 页面只读取脱敏后的 PublicConfig，密钥明文不返回。
 */
@RestController
@RequestMapping("/api/v1/ai/models/config")
public class ScsAiConfigController {

    private final ScsAiConfigService config;
    private final AiModelService modelService;

    public ScsAiConfigController(ScsAiConfigService config, AiModelService modelService) {
        this.config = config;
        this.modelService = modelService;
    }

    @GetMapping
    public ScsAiConfigService.PublicConfig get() {
        return config.publicConfig();
    }

    @PutMapping
    public ScsAiConfigService.PublicConfig put(
            @RequestBody ScsAiConfigService.UpdateRequest request) {
        if (request.model() != null && !request.model().isBlank()) {
            // 先验证模型存在，避免把无效模型写进配置
            modelService.getModel(request.model());
        }
        String actor = com.bproject.safety.config.ScsRequestActor.displayName().orElse("admin");
        ScsAiConfigService.PublicConfig saved = config.update(request, actor);
        if (request.model() != null && !request.model().isBlank()) {
            modelService.selectModel(request.model());
        }
        return saved;
    }
}
