package com.bdemo.sys.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 校验 IAM 签发的 Bearer JWT，并跨 schema 从 IAM 库加载用户角色权限。
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final LoginUserLoader loader;
    private SysSsoSessions nativeSessions;
    public JwtAuthFilter withNativeSessions(SysSsoSessions value){nativeSessions=value;return this;}

    public JwtAuthFilter(JwtService jwtService, LoginUserLoader loader) {
        this.jwtService = jwtService;
        this.loader = loader;
    }

    public static LoginUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser lu) {
            return lu;
        }
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        var session=request.getSession(false);
        if(nativeSessions!=null&&session!=null&&session.getAttribute("SYS_SSO") instanceof SysSsoSessions.Binding binding){
            try{
                LoginUser user=nativeSessions.current(binding);
                boolean write=!java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod());
                String path=request.getRequestURI();
                if(user.isReadOnly()&&(write||path.contains("/export")||path.contains("/download"))&&!path.endsWith("/auth/sso/logout")){
                    response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":403,\"message\":\"只读账号禁止此操作\"}");return;
                }
                if(write){
                    Object expected=session.getAttribute("SYS_CSRF");String actual=request.getHeader("X-SYS-CSRF");
                    if(!(expected instanceof String value)||actual==null||!java.security.MessageDigest.isEqual(value.getBytes(java.nio.charset.StandardCharsets.UTF_8),actual.getBytes(java.nio.charset.StandardCharsets.UTF_8))){
                        response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":403,\"message\":\"CSRF校验失败\"}");return;
                    }
                }
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,AuthorityUtils.NO_AUTHORITIES));
                filterChain.doFilter(request,response);return;
            }catch(org.springframework.web.server.ResponseStatusException failure){
                response.setStatus(failure.getStatusCode().value());response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":"+failure.getStatusCode().value()+",\"message\":\"统一登录会话暂不可用或已失效\"}");return;
            }
        }
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            try {
                var claims = jwtService.parse(token);
                String userId = claims.get("userId", String.class);
                if (userId == null) {
                    userId = claims.getSubject();
                }
                LoginUser loginUser = loader.load(userId);
                if (loginUser != null) {
                    var authentication = new UsernamePasswordAuthenticationToken(
                            loginUser, null, AuthorityUtils.NO_AUTHORITIES);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
