package com.mt.wms.agent;

import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仓储智能体模型服务配置。GET 需要 agent:read，PUT 需要 agent:manage；
 * 页面只读取脱敏后的 PublicConfig，密钥明文不返回。
 */
@RestController
@RequestMapping("/api/v1/agent/config")
class AgentConfigController {

    private final WmsAiConfigService config;

    AgentConfigController(WmsAiConfigService config) {
        this.config = config;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('agent:read') and hasAuthority('inventory:read')")
    ApiResponse<WmsAiConfigService.PublicConfig> get(HttpServletRequest request) {
        return ApiResponse.ok(config.publicConfig(), requestId(request));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('agent:manage') and hasAuthority('inventory:read')")
    ApiResponse<WmsAiConfigService.PublicConfig> put(
            @RequestBody WmsAiConfigService.UpdateRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        String actor = authentication != null ? authentication.getName() : "admin";
        return ApiResponse.ok(config.update(body, actor), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value == null ? "" : value.toString();
    }
}
