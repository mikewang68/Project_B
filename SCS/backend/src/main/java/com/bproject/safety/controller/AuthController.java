package com.bproject.safety.controller;

import com.bproject.safety.support.demo.DemoUserProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前服务端认证主体。固定演示用户仅限显式关闭安全的 demo/test profile。
 * 成功响应严格按接口清单直接返回业务对象，不做 {code,data} 包装。
 */
@RestController
@RequestMapping("/api/v1/auth")
@EnableConfigurationProperties(DemoUserProperties.class)
@Tag(name = "Auth", description = "演示用户")
public class AuthController {
    private final DemoUserProperties user;
    @org.springframework.beans.factory.annotation.Value("${app.security.enabled:true}")
    private boolean securityEnabled;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    public AuthController(DemoUserProperties user) {
        this.user = user;
    }

    @Operation(summary = "当前登录用户与班次")
    @GetMapping("/me")
    public CurrentUser me(jakarta.servlet.http.HttpServletRequest request) {
        Object sub=request.getAttribute(com.bproject.safety.config.SecurityInterceptor.ATTR_USER_SUB);
        if(!(sub instanceof String subject)||subject.isBlank()) {
            if (!securityEnabled && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("demo", "test")))
                return new CurrentUser(user.id(),user.name(),user.role(),user.team(),user.shift(),user.online(),"");
            throw new com.bproject.safety.common.error.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","请先登录统一入口");
        }
        String name=subject,role="USER";
        if(request.getAttribute("SCS_ACTOR") instanceof com.bproject.safety.config.ScsLocalPolicy.Actor actor){
            name=actor.username();role=actor.readOnly()?"READONLY":actor.admin()?"ADMIN":"OPERATOR";
        }
        var session=request.getSession(false);String csrf=session==null?"":java.util.Objects.toString(session.getAttribute("SCS_CSRF"),"");
        return new CurrentUser(subject,name,role,"","",true,csrf);
    }

    /** 与 docs/backend-demo-api-inventory.json 中 /auth/me 的 response 字段一致。 */
    public record CurrentUser(String id, String name, String role, String team, String shift, boolean online, String csrfToken) {
    }
}
