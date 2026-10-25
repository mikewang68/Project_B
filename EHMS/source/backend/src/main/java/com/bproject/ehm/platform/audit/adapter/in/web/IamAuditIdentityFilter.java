package com.bproject.ehm.platform.audit.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Principal;
import java.time.Duration;

/** Optional existing IAM /auth/me integration. This verifies identity, not all EHM business permissions. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class IamAuditIdentityFilter extends OncePerRequestFilter {
    public static final String VERIFIED_ACTOR = IamAuditIdentityFilter.class.getName()+".actor";
    public static final String VERIFIED_READ_ONLY = IamAuditIdentityFilter.class.getName()+".readOnly";
    public static final String VERIFIED_ADMIN = IamAuditIdentityFilter.class.getName()+".admin";
    private final ObjectMapper mapper;
    private final String endpoint;
    private final boolean required;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public IamAuditIdentityFilter(ObjectMapper mapper,@Value("${ehm.audit.iam-me-url:}") String endpoint,@Value("${ehm.audit.iam-required:false}") boolean required){
        this.mapper=mapper;this.endpoint=endpoint;this.required=required;
        if(required && endpoint.isBlank())throw new IllegalArgumentException("Required IAM identity endpoint is not configured");
        if(!endpoint.isBlank() && (!java.util.Set.of("http","https").contains(URI.create(endpoint).getScheme()) || URI.create(endpoint).getUserInfo()!=null))throw new IllegalArgumentException("Invalid IAM identity endpoint");
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !request.getRequestURI().startsWith("/api/ehm/v1/")||java.util.Set.of("/api/ehm/v1/auth/login","/api/ehm/v1/auth/logout","/api/ehm/v1/auth/sso/health","/api/ehm/v1/auth/sso/exchange","/api/ehm/v1/auth/sso/revoke").contains(request.getRequestURI());}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(request.getUserPrincipal()!=null){chain.doFilter(request,response);return;}
        String authorization=request.getHeader("Authorization");
        if(endpoint.isBlank() || authorization==null || !authorization.startsWith("Bearer ")){
            if(required){response.sendError(401);return;}chain.doFilter(request,response);return;
        }
        if(authorization.length()>4096){response.sendError(401);return;}
        Principal actor;
        try{
            var verified=http.send(HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(3)).header("Authorization",authorization).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(verified.statusCode()!=200){response.sendError(verified.statusCode()==401||verified.statusCode()==403?401:503);return;}
            var data=mapper.readTree(verified.body());var user=data.path("data").path("user");String id=user.path("id").asText();
            // A missing/null/string code must not be coerced to success code 0.
            var code=data.path("code");
            if(!code.isIntegralNumber() || !code.canConvertToInt() || !(code.intValue()==0 || code.intValue()==200)
                    || !(user.path("id").isTextual() || user.path("id").isIntegralNumber())
                    || id.isBlank() || id.length()>128 || id.chars().anyMatch(Character::isISOControl)
                    || !user.path("status").asText("active").equals("active")){response.sendError(401);return;}
            actor=()->id;request.setAttribute(VERIFIED_ACTOR,actor);
            boolean readOnly=trustedReadOnly(data.path("data"));
            request.setAttribute(VERIFIED_READ_ONLY,readOnly);
            boolean administrator=false;var roles=data.path("data").path("roles");
            if(roles.isArray())for(var role:roles){String name=role.isTextual()?role.asText():role.path("code").asText();if(java.util.Set.of("SUPER_ADMIN","ROLE_SUPER_ADMIN").contains(name))administrator=true;}
            request.setAttribute(VERIFIED_ADMIN,administrator&&!readOnly);
            // Trusted IAM flags only, never URL parameters or browser-supplied role headers.
            if(readOnly && (!java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())
                    || request.getRequestURI().matches(".*/(export|download|retry)(/.*)?"))){response.sendError(403);return;}
        }catch(InterruptedException e){Thread.currentThread().interrupt();response.sendError(503);return;}
        catch(IOException|RuntimeException e){response.sendError(503);return;}
        chain.doFilter(new jakarta.servlet.http.HttpServletRequestWrapper(request){@Override public Principal getUserPrincipal(){return actor;}},response);
    }
    static boolean trustedReadOnly(com.fasterxml.jackson.databind.JsonNode data) {
        if(data.path("readOnly").isBoolean() && data.path("readOnly").booleanValue())return true;
        if(data.path("user").path("readOnly").isBoolean() && data.path("user").path("readOnly").booleanValue())return true;
        var roles=data.path("roles");
        if(roles.isArray())for(var role:roles){
            String name=role.isTextual()?role.asText():role.path("code").asText();
            if(java.util.Set.of("READONLY_AUDITOR","PLATFORM_AUDITOR_READONLY","ROLE_READONLY_AUDITOR","ROLE_PLATFORM_AUDITOR_READONLY").contains(name))return true;
        }
        return false;
    }
    public static boolean readOnly(HttpServletRequest request){return Boolean.TRUE.equals(request.getAttribute(VERIFIED_READ_ONLY));}
    public static Principal actor(HttpServletRequest request){return request.getUserPrincipal()!=null?request.getUserPrincipal():request.getAttribute(VERIFIED_ACTOR) instanceof Principal p?p:null;}
}
