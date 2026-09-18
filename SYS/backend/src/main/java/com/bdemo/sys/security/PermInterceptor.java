package com.bdemo.sys.security;

import com.bdemo.sys.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class PermInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequirePerm require = handlerMethod.getMethodAnnotation(RequirePerm.class);
        if (require == null) {
            require = handlerMethod.getBeanType().getAnnotation(RequirePerm.class);
        }
        if (require == null || require.value().length == 0) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LoginUser user)) {
            throw BizException.unauthorized("未登录或登录已过期");
        }
        for (String code : require.value()) {
            if (user.hasPermission(code)) {
                return true;
            }
        }
        throw BizException.forbidden("没有操作权限：" + String.join("、", require.value()));
    }
}
