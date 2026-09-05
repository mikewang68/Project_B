package com.bdemo.config;

import com.bdemo.auth.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class SecurityAuditAccessDeniedHandler implements AccessDeniedHandler {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SecurityAuditAccessDeniedHandler(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        Authentication authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        String userName = authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                ? user.userName() : null;
        String role = authentication == null ? null : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(value -> value.startsWith("ROLE_"))
                .map(value -> value.substring(5))
                .findFirst().orElse(null);
        String uri = request.getRequestURI();
        String module = uri.split("/").length > 1 ? uri.split("/")[1] : "unknown";
        jdbc.update("""
                INSERT INTO e_audit_security(event_time, event_type, user_name, user_role,
                  target_module, target_resource, client_ip, user_agent, action_result, remark, create_time)
                VALUES (NOW(), 'unauthorized_access', ?, ?, ?, ?, ?, ?, 'blocked', ?, NOW())
                """, userName, role, module, request.getMethod() + " " + uri,
                request.getRemoteAddr(), request.getHeader("User-Agent"), "Spring Security 角色校验拒绝");
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), java.util.Map.of("code", 403, "msg", "无权限访问"));
    }
}
