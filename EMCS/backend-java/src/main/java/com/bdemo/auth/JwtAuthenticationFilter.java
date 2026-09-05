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

    public JwtAuthenticationFilter(JwtService jwtService, SystemUserMapper systemUserMapper) {
        this.jwtService = jwtService;
        this.systemUserMapper = systemUserMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                AuthenticatedUser user = jwtService.parse(authorization.substring(7));
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
