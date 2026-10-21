package com.bproject.ehm.platform.security;

import com.bproject.ehm.platform.audit.adapter.in.web.IamAuditIdentityFilter;
import com.bproject.ehm.shared.web.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component @Profile("server") @Order(Ordered.HIGHEST_PRECEDENCE+15)
public class LocalSessionFilter extends OncePerRequestFilter {
    public static final String SESSION_TOKEN=LocalSessionFilter.class.getName()+".token";
    private final ObjectProvider<LocalAccountService> accounts;private final ObjectMapper mapper;private final String mode,cookie;
    public LocalSessionFilter(ObjectProvider<LocalAccountService> accounts,ObjectMapper mapper,@Value("${ehm.auth.mode:disabled}") String mode,@Value("${ehm.auth.cookie-name:EHM_SESSION}") String cookie){
        if(!Set.of("local","iam","disabled").contains(mode)||!cookie.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid auth mode/cookie");this.accounts=accounts;this.mapper=mapper;this.mode=mode;this.cookie=cookie;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !mode.equals("local")||!request.getRequestURI().startsWith("/api/ehm/v1/")||request.getMethod().equals("OPTIONS");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        String path=request.getRequestURI(),token=null;int count=0;
        if(request.getCookies()!=null)for(Cookie item:request.getCookies())if(item.getName().equals(cookie)){token=item.getValue();count++;}
        if(count>1){reject(mapper,response,400,"会话Cookie重复，请清除本系统Cookie后重试");return;}
        LocalUser actor=accounts.getObject().authenticate(token);
        if(actor==null){
            if(path.equals("/api/ehm/v1/auth/login")||path.equals("/api/ehm/v1/auth/logout")){chain.doFilter(request,response);return;}
            reject(mapper,response,401,"请先登录设备健康管理系统");return;
        }
        request.setAttribute(SESSION_TOKEN,token);request.setAttribute(IamAuditIdentityFilter.VERIFIED_ACTOR,actor);
        request.setAttribute(IamAuditIdentityFilter.VERIFIED_READ_ONLY,!actor.admin());
        if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())&&!path.equals("/api/ehm/v1/auth/login")){
            String csrf=request.getHeader("X-EHM-CSRF");
            if(csrf==null||!MessageDigest.isEqual(actor.csrfToken().getBytes(StandardCharsets.UTF_8),csrf.getBytes(StandardCharsets.UTF_8))){reject(mapper,response,403,"请求校验失败，请刷新页面后重试");return;}
        }
        chain.doFilter(new HttpServletRequestWrapper(request){@Override public java.security.Principal getUserPrincipal(){return actor;}},response);
    }
    public static void reject(ObjectMapper mapper,HttpServletResponse response,int code,String message) throws IOException{
        response.setStatus(code);response.setContentType("application/json;charset=UTF-8");response.setHeader("Cache-Control","no-store");
        mapper.writeValue(response.getWriter(),Map.of("code",code==401?"AUTH_REQUIRED":"PERMISSION_DENIED","message",message,"traceId",Objects.toString(response.getHeader(TraceIdFilter.HEADER_NAME),"")));
    }
}
