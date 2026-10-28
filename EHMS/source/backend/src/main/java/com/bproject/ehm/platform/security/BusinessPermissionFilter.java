package com.bproject.ehm.platform.security;

import com.bproject.ehm.platform.audit.adapter.in.web.IamAuditIdentityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Set;

/** Two-role baseline; backend enforcement is independent of buttons/role preview/localStorage. */
@Component @Profile("server") @Order(Ordered.HIGHEST_PRECEDENCE+30)
public class BusinessPermissionFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;private final String mode;
    public BusinessPermissionFilter(ObjectMapper mapper,@Value("${ehm.auth.mode:disabled}") String mode){this.mapper=mapper;this.mode=mode;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return mode.equals("disabled")||!request.getRequestURI().startsWith("/api/ehm/v1/")||request.getMethod().equals("OPTIONS");}
    public static boolean admin(HttpServletRequest request){
        var actor=IamAuditIdentityFilter.actor(request);
        return !IamAuditIdentityFilter.readOnly(request)&&(actor instanceof LocalUser local?local.admin():actor!=null&&Boolean.TRUE.equals(request.getAttribute(IamAuditIdentityFilter.VERIFIED_ADMIN)));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        String path=request.getRequestURI();
        if(Set.of("/api/ehm/v1/auth/login","/api/ehm/v1/auth/logout","/api/ehm/v1/auth/sso/exchange","/api/ehm/v1/auth/sso/revoke","/api/ehm/v1/auth/sso/health").contains(path)){chain.doFilter(request,response);return;}
        if(IamAuditIdentityFilter.actor(request)==null){LocalSessionFilter.reject(mapper,response,401,"请先登录");return;}
        if(mode.equals("iam") && IamAuditIdentityFilter.actor(request) instanceof EhmSsoSessions.Actor actor){
            if(!EhmBusinessPermissions.permits(actor,request.getMethod(),path)){
                LocalSessionFilter.reject(mapper,response,403,"当前功能或操作未获授权");return;
            }
            chain.doFilter(request,response);return;
        }
        boolean safe=Set.of("GET","HEAD").contains(request.getMethod());
        boolean ownService=path.equals("/api/ehm/v1/auth/password")||path.equals("/api/ehm/v1/system/client-events")||path.equals("/api/ehm/v1/assistant/chat");
        boolean privileged=path.startsWith("/api/ehm/v1/auth/users")||path.matches(".*/(export|download|backup|restore|retry)(/.*)?");
        if(!admin(request)&&((!safe&&!ownService)||privileged)){
            LocalSessionFilter.reject(mapper,response,403,"一般用户仅可查询；新增、修改、删除及管理操作需要超级管理员权限");return;
        }
        chain.doFilter(request,response);
    }
}
