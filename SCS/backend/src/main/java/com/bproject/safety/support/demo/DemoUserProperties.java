package com.bproject.safety.support.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Demo 阶段固定演示用户（不实现 JWT/OAuth2/SSO/RBAC）。
 * 可通过环境变量 DEMO_USER_ID / DEMO_USER_NAME / DEMO_USER_ROLE /
 * DEMO_USER_TEAM / DEMO_USER_SHIFT 切换演示身份。
 */
@ConfigurationProperties(prefix = "app.demo.user")
public record DemoUserProperties(
        String id,
        String name,
        String role,
        String team,
        String shift,
        boolean online) {

    public DemoUserProperties {
        if (id == null || id.isBlank()) {
            id = "USR-001";
        }
        if (name == null || name.isBlank()) {
            name = "李娜";
        }
        if (role == null || role.isBlank()) {
            role = "安全员";
        }
        if (team == null || team.isBlank()) {
            team = "安全管理组";
        }
        if (shift == null || shift.isBlank()) {
            shift = "夜班";
        }
    }
}
