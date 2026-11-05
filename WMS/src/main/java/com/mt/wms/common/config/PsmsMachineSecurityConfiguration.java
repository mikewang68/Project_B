package com.mt.wms.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mt.wms.stockin.PsmsInboundSettings;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** Only this exact machine namespace is stateless; browser CSRF/SSO stays on the original chain. */
@Configuration(proxyBeanMethods=false)
public class PsmsMachineSecurityConfiguration {
    @Bean @Order(1)
    SecurityFilterChain psmsMachineChain(HttpSecurity http,PsmsInboundSettings settings,ObjectMapper mapper)throws Exception{
        http.securityMatcher("/api/v1/integrations/psms/inbound-tasks", "/api/v1/integrations/psms/inbound-tasks/**")
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(s->s.requireExplicitSave(true))
                .csrf(c->c.disable()).requestCache(c->c.disable()).httpBasic(c->c.disable())
                .formLogin(c->c.disable()).logout(c->c.disable())
                .authorizeHttpRequests(a->a.anyRequest().hasAuthority("psms:inbound"));
        http.addFilterBefore(new OncePerRequestFilter(){
            @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
                SecurityContextHolder.clearContext();
                if(!settings.enabled()) {reject(res,mapper,503,"PSMS_DISABLED","调度入库联动尚未启用");return;}
                if(!directLoopback(req)){reject(res,mapper,403,"PSMS_DIRECT_ONLY","服务接口仅接受本机直接连接");return;}
                if(!settings.accepts(req.getHeader("Authorization"))) {reject(res,mapper,401,"PSMS_UNAUTHENTICATED","服务身份验证失败");return;}
                var context=SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("PSMS",null,List.of(new SimpleGrantedAuthority("psms:inbound"))));
                SecurityContextHolder.setContext(context);
                try{chain.doFilter(req,res);}finally{SecurityContextHolder.clearContext();}
            }
        },AuthorizationFilter.class);
        return http.build();
    }
    private static boolean directLoopback(HttpServletRequest request){
        if(!java.util.Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(request.getRemoteAddr()))return false;
        // Forwarded identities are never trusted on this local service-only endpoint.
        var headers=request.getHeaderNames();
        while(headers.hasMoreElements()){
            String name=headers.nextElement().toLowerCase(java.util.Locale.ROOT);
            if(name.equals("forwarded")||name.startsWith("x-forwarded-")||name.equals("x-real-ip"))return false;
        }
        return true;
    }
    private static void reject(HttpServletResponse res,ObjectMapper mapper,int status,String code,String message)throws IOException{
        res.setStatus(status);res.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(res.getOutputStream(),Map.of("code",code,"message",message,"traceId",java.util.UUID.randomUUID().toString()));
    }
}
