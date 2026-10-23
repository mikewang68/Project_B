package com.bdemo.iam.security;

import com.bdemo.iam.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 阶段 2-B 强制只读硬否决拦截器 (Readonly Hard Veto Interceptor)。
 *
 * 核心安全规则：
 * 1. 作用于真实服务端写 API（不仅限于按钮置灰），拦截一切对 IAM 系统的状态改变操作；
 * 2. 主体持有 PLATFORM_AUDITOR_READONLY 或 readOnly=true 时，硬否决优先级高于一切写权限；
 * 3. 当用户同时被赋予全权角色 (FULL_ADMIN) 与只读角色 (READONLY_AUDITOR) 时，只读硬否决优先；
 * 4. 严禁只读用户修改用户、组织、角色、权限绑定、重置密码、区块链证书操作；
 * 5. 敏感导出接口与具有写副作用的 GET 请求一律拦截（403 Forbidden）；
 * 6. 正常认证登入/登出 (/auth/login, /auth/logout) 与健康探针保留原样，防止拒绝服务。
 */
@Component
public class ReadonlyHardVetoInterceptor implements HandlerInterceptor {

  private static final Logger log = LoggerFactory.getLogger(ReadonlyHardVetoInterceptor.class);

  private static final Set<String> WRITE_HTTP_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    LoginUser user = JwtAuthFilter.currentUser();
    if (user == null) {
      // 未登录请求交由后续 Spring Security 过滤器链或 PermInterceptor 统一返回 401
      return true;
    }

    // 检查主体是否处于强制只读模式
    boolean isReadonly = user.isReadOnly()
        || "READONLY_AUDITOR".equalsIgnoreCase(user.getAccessMode())
        || user.getRoles().stream().anyMatch(r ->
            "PLATFORM_AUDITOR_READONLY".equalsIgnoreCase(r)
            || "auditor_readonly".equalsIgnoreCase(r)
            || "readonly_auditor".equalsIgnoreCase(r)
            || "platform_auditor_readonly".equalsIgnoreCase(r));

    if (!isReadonly) {
      return true;
    }

    String path = request.getRequestURI();
    String method = request.getMethod();

    // A read-only user still needs an authenticated, explicitly mapped SSO session.
    // Only ticket issuance is session maintenance; business POSTs remain forbidden.
    String endpoint = path.substring(request.getContextPath().length());
    if ("POST".equals(method) && "/auth/sso/ticket".equals(endpoint)) {
      return true;
    }

    // 白名单：登录与登出接口保持正常可用（必须支持会话维护与安全退出）
    if (path.endsWith("/auth/login") || path.endsWith("/auth/logout") || path.endsWith("/health")) {
      return true;
    }

    // 1. 拦截所有常规写请求方法 (POST, PUT, PATCH, DELETE)
    if (WRITE_HTTP_METHODS.contains(method.toUpperCase())) {
      log.warn("ReadonlyHardVeto: User {} ({}) attempted write operation {} on URI {}, blocked by hard veto.",
          user.getUsername(), user.getUserId(), method, path);
      throw new BizException(403, 40301, "只读审计模式已启用：禁止执行任何新增、编辑、删除或状态变更操作");
    }

    // 2. 拦截可能带有写副作用或敏感导出语义的 GET 请求
    if ("GET".equalsIgnoreCase(method)) {
      if (path.contains("/export") || hasSensitiveExportOrSideEffect(request)) {
        log.warn("ReadonlyHardVeto: User {} ({}) attempted sensitive export or mutation GET on URI {}, blocked.",
            user.getUsername(), user.getUserId(), path);
        throw new BizException(403, 40301, "只读审计模式已启用：禁止执行敏感数据导出或带写副作用的操作");
      }
    }

    return true;
  }

  private boolean hasSensitiveExportOrSideEffect(HttpServletRequest request) {
    String action = request.getParameter("action");
    if (action != null) {
      String act = action.toLowerCase();
      if (act.contains("export") || act.contains("reset") || act.contains("clear")
          || act.contains("sync") || act.contains("delete")) {
        return true;
      }
    }
    String export = request.getParameter("export");
    return export != null && !"false".equalsIgnoreCase(export);
  }
}
