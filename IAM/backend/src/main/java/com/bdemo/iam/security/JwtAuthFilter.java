package com.bdemo.iam.security;

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
 * 解析 Bearer JWT → 查库加载用户角色权限 → 写入 SecurityContext。
 * 无 token / token 失效不直接拒绝，交由后续授权链返回 401/403。
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final LoginUserLoader loader;
    private com.bdemo.iam.sso.SsoAuthorizationService nativeAuthorization;
    public JwtAuthFilter withNativeAuthorization(com.bdemo.iam.sso.SsoAuthorizationService service) {
        nativeAuthorization=service;return this;
    }

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
        var nativeSession=request.getSession(false);
        if(nativeAuthorization!=null&&nativeSession!=null
            &&nativeSession.getAttribute("IAM_NATIVE_SUBJECT") instanceof String subject
            &&nativeSession.getAttribute("IAM_NATIVE_SID") instanceof String sid){
            try{
                nativeAuthorization.current(subject,sid,"iam");
                if(!java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())){
                    Object expected=nativeSession.getAttribute("IAM_NATIVE_CSRF");String actual=request.getHeader("X-IAM-CSRF");
                    if(!(expected instanceof String value)||actual==null||!java.security.MessageDigest.isEqual(
                        value.getBytes(java.nio.charset.StandardCharsets.UTF_8),actual.getBytes(java.nio.charset.StandardCharsets.UTF_8))){
                        response.setStatus(403);response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"code\":403,\"message\":\"CSRF校验失败\"}");return;
                    }
                }
                LoginUser user=loader.load(subject);
                if(user==null)throw new com.bdemo.iam.sso.SsoTicketService.SsoTicketException("账号不可用");
                request.setAttribute("iam.sessionId",sid);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,AuthorityUtils.NO_AUTHORITIES));
                filterChain.doFilter(request,response);return;
            }catch(com.bdemo.iam.sso.SsoTicketService.SsoTicketException denied){
                nativeSession.invalidate();response.setStatus(401);response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"message\":\"会话或授权已失效\"}");return;
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
                // 会话 id（JWT 的 jti）供 SSO 票据注销联动使用。
                // 只写入请求属性，不进入日志、不回传前端。
                if (claims.getId() != null) {
                    request.setAttribute("iam.sessionId", claims.getId());
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
