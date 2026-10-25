package com.mt.wms.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Validate persistent accounts and grants on each API request, including existing sessions. */
public class ActiveAccountFilter extends OncePerRequestFilter {
    private final AuthRepository repository;
    private final ObjectMapper mapper;
    public ActiveAccountFilter(AuthRepository repository,ObjectMapper mapper) { this.repository=repository;this.mapper=mapper; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getServletPath();
        return !path.startsWith("/api/") || path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/csrf");
    }

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication!=null && authentication.getPrincipal() instanceof WmsPrincipal previous) {
            var refreshed=repository.refreshPrincipal(previous);
            if(refreshed.isEmpty()) {
                SecurityContextHolder.clearContext();
                var session=request.getSession(false);
                if(session!=null) session.invalidate();
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                mapper.writeValue(response.getOutputStream(),ApiResponse.error("AUTH_UNAUTHENTICATED","账号已停用或删除，请重新登录",String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE))));
                return;
            }
            WmsPrincipal principal=refreshed.get();
            if (request.getAttribute("WMS_IAM_AUTHORIZATION") instanceof com.fasterxml.jackson.databind.JsonNode current)
                principal=com.mt.wms.auth.sso.WmsCurrentAuthorization.apply(principal,current);
            // Use a new context to avoid mutating one shared by concurrent requests on the same session.
            var context=SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request,response);
    }
}
