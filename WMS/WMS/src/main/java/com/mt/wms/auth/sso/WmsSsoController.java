package com.mt.wms.auth.sso;

import com.mt.wms.auth.AuthModels;
import com.mt.wms.auth.AuthService;
import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WMS 可信 SSO 入口（契约 V1.1）。
 *
 * <p>与原生登录的区别：
 *
 * <ul>
 *   <li>不接收用户名/密码，不接触密码策略；{@code passwordChangeRequired} 仍由本地账号真实状态决定。
 *   <li>票据一次性，兑换后立即失效（由 IAM 原子消费）。
 *   <li>会话绑定 IAM {@code sid}，供全局注销联动（见 {@link WmsSsoLogoutRegistry}）。
 * </ul>
 *
 * <p>本端点不修改任何既有登录/CSRF/密码行为。
 */
@RestController
@RequestMapping("/api/v1/auth/sso")
public class WmsSsoController {

  private final WmsSsoExchangeService exchangeService;
  private final WmsSsoProperties properties;
  private final WmsSsoSourceGuard sourceGuard;
  private final AuthService authService;
  private final SecurityContextRepository securityContextRepository;
  private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

  public WmsSsoController(
      WmsSsoExchangeService exchangeService,
      WmsSsoProperties properties,
      WmsSsoSourceGuard sourceGuard,
      AuthService authService,
      SecurityContextRepository securityContextRepository) {
    this.exchangeService = exchangeService;
    this.properties = properties;
    this.sourceGuard = sourceGuard;
    this.authService = authService;
    this.securityContextRepository = securityContextRepository;
  }

  /**
   * 用 IAM 票据换取 WMS 本地会话。
   *
   * <p>票据支持请求头 {@code X-SSO-Ticket} 或 JSON 字段 {@code ticket}。
   * 不接受 URL 查询参数（避免票据进入访问日志与浏览器历史）。
   */
  @PostMapping("/exchange")
  Object exchange(
      @RequestBody(required = false) ExchangeRequest body,
      @RequestHeader(value = "X-SSO-Ticket", required = false) String headerTicket,
      HttpServletRequest request,
      HttpServletResponse response) {

    String ticket = headerTicket != null && !headerTicket.isBlank()
        ? headerTicket.trim()
        : (body == null ? null : body.ticket());

    if (!properties.isUsable()) {
      response.setStatus(503);
      return ApiResponse.error("SSO_NOT_CONFIGURED", "WMS 可信 SSO 未配置", requestId(request));
    }

    // 止血：签票密钥泄露期间，exchange 只接受可信来源。
    // 这是临时缓解，不替代密钥轮换。
    if (!sourceGuard.isAllowed(request)) {
      response.setStatus(403);
      return ApiResponse.error("SSO_SOURCE_DENIED", "请求来源不在允许范围内", requestId(request));
    }

    WmsSsoExchangeService.Result result = exchangeService.exchange(ticket);
    if (!result.ok()) {
      response.setStatus(switch (result.errorCode()) {
        case "SSO_NO_LOCAL_MAPPING", "SSO_FORBIDDEN", "SSO_ZONE_UNSUPPORTED", "SSO_SCOPE_MISMATCH" -> 403;
        case "SSO_NOT_CONFIGURED", "SSO_IAM_UNAVAILABLE" -> 503;
        case "SSO_TICKET_MISSING" -> 400;
        default -> 401;
      });
      // 未建立会话；不写 Cookie，不留下半成品
      return ApiResponse.error(result.errorCode(), result.message(), requestId(request));
    }

    WmsPrincipal principal = result.principal();
    if (principal.companyId() == null || principal.companyId() < 0) {
      response.setStatus(403);
      return ApiResponse.error("SSO_NO_COMPANY", "本地主体缺少有效公司", requestId(request));
    }

    // 与原生 /login 保持完全相同的顺序：先建会话、再 changeSessionId，
    // 最后保存 SecurityContext。顺序不同会导致 JSESSIONID 与已保存的上下文不匹配。
    // 与原生 /login 完全一致的顺序：
    //   getSession(true) -> changeSessionId() -> saveContext()
    HttpSession session = request.getSession(true);
    request.changeSessionId();
    // changeSessionId() 之后，旧的 HttpSession 引用上 getId() 可能仍返回旧 id，
    // 而下发给浏览器的 JSESSIONID 是新 id。必须重新取一次会话，
    // 否则绑定键与请求时的 session id 不一致，撤销检查会命中错误条目。
    HttpSession boundSession = request.getSession(false);
    if (boundSession == null) {
      boundSession = session;
    }
    Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
        principal, null, principal.getAuthorities());
    SecurityContext context = holder.createEmptyContext();
    context.setAuthentication(authentication);
    holder.setContext(context);
    securityContextRepository.saveContext(context, request, response);

    // 打 SSO 标记：Guard 靠它区分"SSO 会话"与"原生登录会话"，
    // 绑定缺失时才不会把 SSO 会话误判为原生会话而放行。
    boundSession.setAttribute(WmsSsoSessionGuardFilter.ATTR_SSO_ESTABLISHED, Boolean.TRUE);

    // 记录 sid 绑定，供 IAM 全局注销时撤销本地会话
    WmsSsoLogoutRegistry.bind(boundSession.getId(), result.iamSessionId(), result.iamSubject());

    return ApiResponse.ok(authService.currentUser(principal, session), requestId(request));
  }

  /**
   * 注销联动入口：I AM 撤销 sid 后通知 WMS 失效对应本地会话。
   *
   * <p>必须由持有服务身份凭据的 IAM 侧调用；浏览器调用会被拒绝。
   * 处理方式：标记 sid 已撤销 + 立即解绑；下次请求由
   * {@link WmsSsoSessionGuardFilter} 销毁 HttpSession 并返回 401。
   */
  @PostMapping("/revoke")
  Object revoke(
      @RequestBody(required = false) RevokeRequest body,
      @RequestHeader(value = "X-SSO-Service-Token", required = false) String serviceToken,
      HttpServletRequest request, HttpServletResponse response) {

    if (!properties.isUsable()
        || serviceToken == null
        || !java.security.MessageDigest.isEqual(
            properties.getServiceToken().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            serviceToken.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
      response.setStatus(403);
      return ApiResponse.error("SSO_FORBIDDEN", "服务身份无效", requestId(request));
    }

    String sid = body == null ? null : body.iamSessionId();
    if (sid == null || sid.isBlank()) {
      response.setStatus(400);
      return ApiResponse.error("SSO_BAD_REQUEST", "缺少 iamSessionId", requestId(request));
    }

    // 关键：撤销时**不能**解绑。原实现先 markRevoked 再 unbindByIamSid，
    // 把 Guard 的检查依据删掉了，导致旧 JSESSIONID 继续可用（G5-4 根因）。
    // 现在保留绑定、打撤销标志，并额外按本地 session id 记录撤销集合。
    java.util.Set<String> affected = WmsSsoLogoutRegistry.revokeByIamSid(sid);

    java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put("revoked", true);
    out.put("iamSessionId", sid);
    out.put("wmsSessionsRevoked", affected.size());
    out.put("wmsSessionFound", !affected.isEmpty());
    return ApiResponse.ok(out, requestId(request));
  }

  /** 诊断端点：不泄露任何凭据，仅告知是否启用。 */
  @org.springframework.web.bind.annotation.GetMapping("/health")
  Object health(HttpServletRequest request) {
    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("enabled", properties.isUsable());
    body.put("audience", properties.getAudience());
    body.put("contractVersion", "1.1");
    body.put("consumptionPoint", "CENTRALIZED_IAM");
    body.put("mode", "server-to-server");
    return ApiResponse.ok(body, requestId(request));
  }

  private static String requestId(HttpServletRequest request) {
    Object id = request.getAttribute(RequestIdFilter.ATTRIBUTE);
    return id == null ? null : String.valueOf(id);
  }

  public record ExchangeRequest(String ticket) {}

  public record RevokeRequest(String iamSessionId) {}
}
