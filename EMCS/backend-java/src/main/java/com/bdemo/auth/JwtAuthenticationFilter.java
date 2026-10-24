package com.bdemo.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.bdemo.system.SystemUserMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    // REQ-076: reject invalid/expired credentials before business handlers.
    private final JwtService jwtService;
    private final SystemUserMapper systemUserMapper;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<EmcsSsoSessions> sso;

    public JwtAuthenticationFilter(JwtService jwtService, SystemUserMapper systemUserMapper) {
        this.jwtService = jwtService;
        this.systemUserMapper = systemUserMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        var session=request.getSession(false);
        if(session!=null&&session.getAttribute("EMCS_SSO") instanceof EmcsSsoSessions.Binding binding){
            try{
                var current=sso.getObject().current(binding);
                boolean write=!java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod());String path=request.getRequestURI();
                if(current.readOnly()&&(write||path.matches(".*/(export|download)(/.*)?"))&&!path.equals("/logout")){
                    response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":403,\"msg\":\"只读账号禁止此操作\"}");return;
                }
                if(!sso.getObject().permits(binding,current,request.getMethod(),path)){
                    response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":403,\"msg\":\"业务接口未获当前权限授权\"}");return;
                }
                if(write){String actual=request.getHeader("X-EMCS-CSRF");Object expected=session.getAttribute("EMCS_CSRF");
                    if(!(expected instanceof String token)||actual==null||!java.security.MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8))){
                        response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":403,\"msg\":\"CSRF校验失败\"}");return;
                    }
                }
                var authorities=Stream.concat(current.roles().stream().map(role->"ROLE_"+role),current.permissions().stream()).map(SimpleGrantedAuthority::new).toList();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(current.principal(),null,authorities));
                request.setAttribute("EMCS_SSO_CURRENT",current);chain.doFilter(request,response);return;
            }catch(org.springframework.web.server.ResponseStatusException failure){response.setStatus(failure.getStatusCode().value());response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":"+failure.getStatusCode().value()+",\"msg\":\"统一身份或业务会话不可用\"}");return;}
        }
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                AuthenticatedUser user = jwtService.parse(authorization.substring(7));
                if(systemUserMapper.findById(user.userId()).filter(account->"0".equals(account.status())&&"0".equals(account.delFlag())).isEmpty())
                    throw new IllegalArgumentException("Account unavailable");
                List<SimpleGrantedAuthority> authorities = Stream.concat(
                                systemUserMapper.findRoleKeys(user.userId()).stream().map(role -> "ROLE_" + role),
                                systemUserMapper.findPermissions(user.userId()).stream())
                        .distinct()
                        .map(SimpleGrantedAuthority::new)
                        .toList();
                var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (RuntimeException exception) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write("{\"code\":401,\"msg\":\"登录状态已过期\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
