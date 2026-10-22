package com.bdemo.auth;

import com.bdemo.auth.sso.EmcsSsoAccess;
import com.bdemo.auth.sso.EmcsSsoPolicy;
import com.bdemo.auth.sso.EmcsSsoSessionRepository;
import com.bdemo.system.SystemUserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.stream.Stream;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final SystemUserMapper users;
    private final EmcsSsoSessionRepository sessions;
    public JwtAuthenticationFilter(JwtService jwt,SystemUserMapper users,EmcsSsoSessionRepository sessions){this.jwt=jwt;this.users=users;this.sessions=sessions;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String authorization=request.getHeader("Authorization");
        if(authorization!=null && authorization.startsWith("Bearer ")){
            try {
                String token=authorization.substring(7);
                EmcsSsoAccess access=token.startsWith(EmcsSsoSessionRepository.PREFIX)?sessions.findActive(token).orElseThrow(()->new IllegalArgumentException("inactive")):null;
                AuthenticatedUser parsed=access==null?jwt.parse(token):access.user();
                var current=users.findById(parsed.userId()).filter(u->"0".equals(u.status()) && "0".equals(u.delFlag())).orElseThrow(()->new IllegalArgumentException("inactive user"));
                if(access!=null && users.findRoleKeys(parsed.userId()).contains("dispatch")) {
                    if(!"*".equals(access.scopeArea()) && !"AREA-A".equals(access.scopeArea()))throw new IllegalArgumentException("scope mismatch");
                    access=new EmcsSsoAccess(access.user(),access.subject(),access.iamSessionId(),true,"AREA-A");
                }
                var principal=new AuthenticatedUser(current.userId(),current.userName());
                var authorities=Stream.concat(users.findRoleKeys(principal.userId()).stream().map(role->"ROLE_"+role),users.findPermissions(principal.userId()).stream())
                        .distinct().map(SimpleGrantedAuthority::new).toList();
                var authentication=new UsernamePasswordAuthenticationToken(principal,null,authorities);
                authentication.setDetails(access);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                String required=EmcsSsoPolicy.requiredPermission(request.getRequestURI().substring(request.getContextPath().length()));
                if(access!=null && required!=null && authorities.stream().noneMatch(a->a.getAuthority().equals("ROLE_admin") || a.getAuthority().equals(required))){
                    reject(response,403,"本地账号没有该能源功能权限");return;
                }
                if(access!=null && !EmcsSsoPolicy.allowed(access,request.getMethod(),request.getRequestURI().substring(request.getContextPath().length()))){
                    reject(response,403,"当前SSO身份无此操作或数据范围权限");return;
                }
            } catch(DataAccessException e){SecurityContextHolder.clearContext();reject(response,503,"认证数据暂不可用");return;}
            catch(RuntimeException e){SecurityContextHolder.clearContext();reject(response,401,"登录状态已过期");return;}
        }
        chain.doFilter(request,response);
    }
    private void reject(HttpServletResponse response,int code,String message)throws IOException{
        response.setStatus(code);response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":"+code+",\"msg\":\""+message+"\"}");
    }
}
