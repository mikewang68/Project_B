package com.bproject.ehm.assistant.adapter.in.web;

import com.bproject.ehm.assistant.application.AiConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 服务配置设置接口。写操作由 BusinessPermissionFilter 强制管理员；
 * 页面只读取脱敏后的 PublicConfig，密钥明文不返回。
 */
@RestController
@RequestMapping("/api/ehm/v1/assistant/config")
public class AssistantConfigController {

    private final AiConfigService config;

    public AssistantConfigController(AiConfigService config) {
        this.config = config;
    }

    @GetMapping
    public AiConfigService.PublicConfig get() {
        return config.publicConfig();
    }

    @PutMapping
    public AiConfigService.PublicConfig put(@RequestBody AiConfigService.UpdateRequest request,
                                            HttpServletRequest http) {
        String actor = http.getRemoteUser();
        if (actor == null || actor.isBlank()) {
            actor = "admin";
        }
        return config.update(request, actor);
    }
}
