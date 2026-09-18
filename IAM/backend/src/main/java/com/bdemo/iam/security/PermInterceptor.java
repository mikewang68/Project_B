package com.bdemo.iam.security;

import com.bdemo.iam.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 在 Controller 方法上按 @RequirePerm 强制校验权限编码（安全边界，不依赖前端 v-perm）。
 */
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
