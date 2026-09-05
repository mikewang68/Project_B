package com.bproject.safety.controller;

import com.bproject.safety.support.demo.DemoUserProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录用户（Demo 固定用户，后续阶段再实现真实认证）。
 * 成功响应严格按接口清单直接返回业务对象，不做 {code,data} 包装。
 */
@RestController
@RequestMapping("/api/v1/auth")
@EnableConfigurationProperties(DemoUserProperties.class)
@Tag(name = "Auth", description = "演示用户")
public class AuthController {
    private final DemoUserProperties user;

    public AuthController(DemoUserProperties user) {
        this.user = user;
    }

    @Operation(summary = "当前登录用户与班次")
    @GetMapping("/me")
    public CurrentUser me() {
        return new CurrentUser(user.id(), user.name(), user.role(), user.team(), user.shift(), user.online());
    }

    /** 与 docs/backend-demo-api-inventory.json 中 /auth/me 的 response 字段一致。 */
    public record CurrentUser(String id, String name, String role, String team, String shift, boolean online) {
    }
}
