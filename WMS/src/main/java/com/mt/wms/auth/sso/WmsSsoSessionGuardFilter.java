package com.mt.wms.auth.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.auth.WmsReadOnlyPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * SSO 会话的中央撤销检查。
 *
 * <p>Portal 登出会撤销 IAM 会话，但浏览器仍可能持有旧的 WMS {@code JSESSIONID}。
 * 只删 Cookie 不算注销完成——本过滤器在**每个受保护 API 请求**上确认本地会话未被撤销，
 * 一旦命中撤销立即 {@code session.invalidate()} 并返回 401。
 *
 * <p><b>G5 修复</b>：
 *
 * <ul>
 *   <li>判据从"绑定存在且 sid 被撤销"改为"本地 session 已被撤销 **或** 绑定的 IAM sid 被撤销"。
 *       原实现依赖绑定存在，而撤销动作同时删除了绑定，导致检查永久失效。
 *   <li>绑定缺失时不再无条件放行：若会话带 {@code SSO_ESTABLISHED} 标记却查不到绑定
 *       （例如注册表被清理），按 fail-closed 处理，返回 401 而不是放行。
 *   <li>注销失败不再静默吞掉：invalidate 抛异常时记录日志并仍然拒绝请求。
 * </ul>
 *
 * <p>原生密码登录的会话不受影响：它们既没有 {@code SSO_ESTABLISHED} 标记也没有绑定。
 */
public class WmsSsoSessionGuardFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(WmsSsoSessionGuardFilter.class);

  /** SSO 建立的会话在 HttpSession 上打的标记，用于区分原生登录。 */
  public static final String ATTR_SSO_ESTABLISHED = "WMS_SSO_ESTABLISHED";

  private final ObjectMapper mapper;
  private WmsSsoExchangeService authorization;
  public WmsSsoSessionGuardFilter withAuthorization(WmsSsoExchangeService value) {authorization=value;return this;}

  public WmsSsoSessionGuardFilter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // 使用 requestURI：Boot 3 的 getServletPath() 在部分配置下可能为空字符串。
    String path = WmsReadOnlyPolicy.requestPath(request);
    return !path.startsWith("/api/")
        || path.equals("/api/v1/auth/login")
        || path.equals("/api/v1/auth/csrf")
        // LogoutFilter still enforces CSRF. Its handler performs central logout and always clears locally.
        || (path.equals("/api/v1/auth/logout") && "POST".equals(request.getMethod()))
        || path.startsWith("/api/v1/auth/sso/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    HttpSession session = request.getSession(false);
    if (session == null) {
      chain.doFilter(request, response);
      return;
    }

    String sid = session.getId();
    WmsSsoLogoutRegistry.Binding binding = WmsSsoLogoutRegistry.get(sid);
    boolean ssoEstablished = Boolean.TRUE.equals(session.getAttribute(ATTR_SSO_ESTABLISHED));
    boolean revoked =
        WmsSsoLogoutRegistry.isSessionRevoked(sid)
            || (binding != null
                && (binding.revoked() || WmsSsoLogoutRegistry.isRevoked(binding.iamSessionId())));

    // 诊断头：只给分类，不含任何凭据或 sid 原文。
    response.setHeader("X-Sso-Guard", revoked
        ? "revoked"
        : (binding != null ? "bound" : (ssoEstablished ? "binding-missing" : "no-binding")));

    if (revoked) {
      destroyAndReject(session, request, response, "IAM 会话已注销，WMS 会话同时失效");
      return;
    }

    if (binding == null && ssoEstablished) {
      // SSO 建立的会话却查不到绑定：注册表被清理或进程内状态不一致。
      // 不能当作原生会话放行，否则撤销检查可被绕过。
      log.warn("SSO 会话缺少绑定，按 fail-closed 拒绝: path={}", request.getRequestURI());
      destroyAndReject(session, request, response, "SSO 会话状态不一致，请重新登录");
      return;
    }

    if(binding!=null&&authorization!=null){
      try{
        WmsPrincipal established = null;
        Object saved = session.getAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (saved instanceof org.springframework.security.core.context.SecurityContext context
            && context.getAuthentication() != null && context.getAuthentication().getPrincipal() instanceof WmsPrincipal principal)
          established = principal;
        if (established == null) {
          destroyAndReject(session, request, response, "SSO 本地身份缺失，请重新登录");
          return;
        }
        var current=authorization.currentAuthorization(binding.iamSubject(),binding.iamSessionId(),established);
        request.setAttribute("WMS_IAM_AUTHORIZATION",current);
        request.setAttribute("WMS_IAM_READONLY",current.path("readOnly").asBoolean());
        if(current.path("readOnly").asBoolean() && WmsReadOnlyPolicy.denies(request.getMethod(),WmsReadOnlyPolicy.requestPath(request))){
          response.setStatus(403);response.setContentType("application/json;charset=UTF-8");
          mapper.writeValue(response.getOutputStream(),ApiResponse.error("AUTH_READONLY","统一身份为只读",null));return;
        }
      }catch(SecurityException denied){destroyAndReject(session,request,response,"统一身份或授权已失效");return;}
      catch(IllegalStateException unavailable){
        response.setStatus(503);response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getOutputStream(),ApiResponse.error("SSO_IAM_UNAVAILABLE","身份服务暂不可用",null));return;
      }
    }
    chain.doFilter(request, response);
  }

  private void destroyAndReject(
      HttpSession session, HttpServletRequest request, HttpServletResponse response, String message)
      throws IOException {
    WmsLocalLogout.clear(request, response);

    response.setStatus(401);
    response.setContentType("application/json;charset=UTF-8");
    mapper.writeValue(
        response.getOutputStream(),
        ApiResponse.error(
            "AUTH_SESSION_REVOKED",
            message,
            String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE))));
  }

  /** 日志里的 sid 只保留前 8 位，避免完整会话标识进入日志。 */
  private static String mask(String sid) {
    if (sid == null) {
      return "null";
    }
    return sid.length() <= 8 ? "***" : sid.substring(0, 8) + "***";
  }
}
