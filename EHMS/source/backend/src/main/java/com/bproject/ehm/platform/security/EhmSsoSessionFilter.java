package com.bproject.ehm.platform.security;

import com.bproject.ehm.platform.audit.adapter.in.web.IamAuditIdentityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Profile("server") @ConditionalOnProperty(name="ehm.auth.mode",havingValue="iam")
@Order(Ordered.HIGHEST_PRECEDENCE+15)
public class EhmSsoSessionFilter extends OncePerRequestFilter {
    private final EhmSsoSessions sessions;private final ObjectMapper mapper;
    public EhmSsoSessionFilter(EhmSsoSessions sessions,ObjectMapper mapper){this.sessions=sessions;this.mapper=mapper;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        String path=request.getRequestURI();return !path.startsWith("/api/ehm/v1/")||Set.of("/api/ehm/v1/auth/sso/exchange","/api/ehm/v1/auth/sso/revoke","/api/ehm/v1/auth/sso/health").contains(path)||request.getMethod().equals("OPTIONS");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        var session=request.getSession(false);EhmSsoSessions.Actor actor;
        try{actor=sessions.actor(session);}catch(org.springframework.web.server.ResponseStatusException failure){
            LocalSessionFilter.reject(mapper,response,failure.getStatusCode().value(),"统一身份或项目授权不可用");return;
        }
        if(actor==null){response.setHeader("X-Platform-Login-Required","1");LocalSessionFilter.reject(mapper,response,401,"请从统一入口登录");return;}
        if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())){
            String expected=(String)session.getAttribute(EhmSsoSessions.CSRF),presented=request.getHeader("X-EHM-CSRF");
            if(presented==null||expected==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),presented.getBytes(StandardCharsets.UTF_8))){LocalSessionFilter.reject(mapper,response,403,"请求校验失败");return;}
        }
        String project=request.getHeader("X-Project-Id"),query=request.getParameter("projectId");
        if(project!=null&&query!=null&&!project.equals(query)){LocalSessionFilter.reject(mapper,response,403,"项目选择不一致");return;}
        if(project==null||project.isBlank())project=query;
        if(project==null||project.isBlank())project=actor.projects().stream().sorted().findFirst().orElse("");
        if(!actor.projects().contains(project)){LocalSessionFilter.reject(mapper,response,403,"未获此项目授权");return;}
        request.setAttribute("EHM_PROJECT_ID",project);
        request.setAttribute(IamAuditIdentityFilter.VERIFIED_ACTOR,actor);request.setAttribute(IamAuditIdentityFilter.VERIFIED_READ_ONLY,actor.readOnly());request.setAttribute(IamAuditIdentityFilter.VERIFIED_ADMIN,actor.admin());
        try(var scope=ProjectContext.bind(project)){
            chain.doFilter(new HttpServletRequestWrapper(request){@Override public java.security.Principal getUserPrincipal(){return actor;}},response);
        }catch(IOException|ServletException failure){throw failure;}catch(Exception failure){throw new ServletException(failure);}
    }
}
