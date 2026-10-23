package com.bproject.safety.controller;

import com.bproject.safety.common.web.TraceIdFilter;
import com.bproject.safety.config.ScsIamTicketValidator;
import com.bproject.safety.config.ScsSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCS 可信 SSO 入口（G6）。
 *
 * <p>契约：IAM 短票是一次性凭据，<b>不得</b>被前端反复用作业务请求凭据。
 * 本控制器把票据换成一次性本地会话：
 *
 * <ol>
 *   <li>校验票据（若配置了 JWT 密钥走本地验签；若配置了 IAM 验票走集中消费）；
 *   <li>建立 servlet 会话并写入主体；
 *   <li>后续业务请求由 {@link com.bproject.safety.config.SecurityInterceptor} 用会话身份放行，
 *       不再要求前端每次携带票据。
 * </ol>
 *
 * <p>票据只在此端点出现一次；不写入 URL、不回显。
 */
@RestController
@RequestMapping("/api/v1/auth/sso")
public class ScsSsoController {

  private static final Logger log = LoggerFactory.getLogger(ScsSsoController.class);

  private final ScsIamTicketValidator ticketValidator;
  private final ObjectMapper mapper;
  @org.springframework.beans.factory.annotation.Autowired private com.bproject.safety.config.ScsLocalPolicy localPolicy;

  /** 是否启用 IAM 集中验票（未配置时返回 SSO_NOT_CONFIGURED）。 */
  @Value("${app.security.jwt-secret:}")
  private String jwtSecret;

  public ScsSsoController(
      ScsIamTicketValidator ticketValidator,
      ObjectMapper mapper) {
    this.ticketValidator = ticketValidator;
    this.mapper = mapper;
  }

  /** 用一次性票据换取本地会话。 */
  @PostMapping("/exchange")
  public ResponseEntity<Map<String, Object>> exchange(
      @RequestBody(required = false) ExchangeRequest body,
      @RequestHeader(value = "X-SSO-Ticket", required = false) String headerTicket,
      HttpServletRequest request,
      HttpServletResponse response) {

    String ticket = headerTicket != null && !headerTicket.isBlank()
        ? headerTicket.trim()
        : (body == null ? null : body.ticket());

    if (ticket == null || ticket.isBlank()) {
      return error("SSO_TICKET_MISSING", "缺少票据", request);
    }
    if (!ticketValidator.isUsable()) {
      return error("SSO_NOT_CONFIGURED", "SCS 未配置 IAM 验票通道", request);
    }
    String service=request.getHeader("X-SSO-Service-Token");
    if(service==null||!constantTimeEquals(ticketValidator.serviceToken(),service))return error("SSO_FORBIDDEN","服务身份无效",request);

    Map<String, Object> claims;
    try {
      claims = ticketValidator.validate(ticket);
    } catch (ScsIamTicketValidator.InvalidTicketException e) {
      log.warn("拒绝 SSO 票据: {}", e.getMessage());
      return error("SSO_TICKET_REJECTED", "票据无效或已使用", request);
    }

    String subject = String.valueOf(claims.getOrDefault("sub", ""));
    if (subject.isBlank()) {
      return error("SSO_BAD_ASSERTION", "票据缺少主体", request);
    }

    com.bproject.safety.config.ScsLocalPolicy.Actor actor;
    try{actor=localPolicy.resolve(claims);}catch(ScsIamTicketValidator.InvalidTicketException denied){return error("SSO_FORBIDDEN","业务映射不可用",request);}
    Set<String> roles = actor.roles();
    Set<String> permissions = actor.permissions();
    String scope = String.valueOf(claims.getOrDefault("scope", ""));
    String iamSid = String.valueOf(claims.getOrDefault("sessionId", ""));

    // 建立本地会话（防会话固定：先建再 changeSessionId）
    HttpSession session = request.getSession(true);
    request.changeSessionId();
    HttpSession bound = request.getSession(false);
    if (bound == null) {
      bound = session;
    }
    bound.setAttribute("SCS_SSO_ESTABLISHED", Boolean.TRUE);
    bound.setAttribute("SCS_SSO_SUBJECT", subject);
    bound.setAttribute("SCS_SSO_ROLES", roles);
    bound.setAttribute("SCS_SSO_PERMISSIONS", permissions);
    bound.setAttribute("SCS_SSO_SCOPE", scope);
    bound.setAttribute("SCS_CSRF",java.util.UUID.randomUUID().toString()+java.util.UUID.randomUUID());

    ScsSessionRegistry.bind(bound.getId(), subject, iamSid, roles, permissions, scope);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("subject", subject);
    out.put("roles", roles);
    out.put("permissions", permissions);
    out.put("scope", scope);
    return ok(out, request);
  }

  @PostMapping("/logout")
  public ResponseEntity<Map<String,Object>> logout(HttpServletRequest request) {
    var session = request.getSession(false);
    var binding = session == null ? null : ScsSessionRegistry.get(session.getId());
    if (binding == null) return error("SSO_TICKET_REJECTED", "会话不存在", request);
    try { ticketValidator.logout(binding.iamSubject(), binding.iamSid()); }
    catch (ScsIamTicketValidator.InvalidTicketException denied) {
      return error("SSO_NOT_CONFIGURED", "统一退出暂不可用，请重试", request);
    }
    ScsSessionRegistry.revokeByIamSid(binding.iamSid());
    session.invalidate();
    return ok(Map.of("loggedOut", true), request);
  }

  /** IAM 注销联动：撤销该 IAM 会话对应的全部 SCS 会话。 */
  @PostMapping("/revoke")
  public ResponseEntity<Map<String, Object>> revoke(
      @RequestBody(required = false) RevokeRequest body,
      @RequestHeader(value = "X-SSO-Service-Token", required = false) String serviceToken,
      HttpServletRequest request) {

    if (!ticketValidator.isUsable() || serviceToken == null || serviceToken.isBlank()
        || !constantTimeEquals(ticketValidator.serviceToken(), serviceToken)) {
      // 诊断：只记录长度与是否为空，绝不记录令牌内容。
      // 这条日志用于区分"调用方没传"与"传了但不匹配"。
      log.warn("revoke 服务身份校验失败: configured_len={}, presented_len={}, usable={}",
          ticketValidator.serviceToken() == null ? -1 : ticketValidator.serviceToken().length(),
          serviceToken == null ? -1 : serviceToken.trim().length(),
          ticketValidator.isUsable());
      return error("SSO_FORBIDDEN", "服务身份无效", request);
    }
    String sid = body == null ? null : body.iamSessionId();
    if (sid == null || sid.isBlank()) {
      return error("SSO_BAD_REQUEST", "缺少 iamSessionId", request);
    }

    int affected = ScsSessionRegistry.revokeByIamSid(sid);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("revoked", true);
    out.put("iamSessionId", sid);
    out.put("scsSessionsRevoked", affected);
    return ok(out, request);
  }

  /** 诊断端点：不泄露任何凭据。 */
  @GetMapping("/health")
  public ResponseEntity<Map<String, Object>> health(HttpServletRequest request) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("enabled", ticketValidator.isUsable());
    out.put("contractVersion", "1.1");
    out.put("consumptionPoint", "CENTRALIZED_IAM");
    out.put("localSessionMode", true);
    // 诊断字段：只给数量，不含任何 sid 或主体，便于确认会话是否真的建立了。
    // 生产排障时"exchange 200 但撤销影响 0"最常见的成因就是会话没落库。
    out.put("activeSessions", ScsSessionRegistry.size());
    out.put("revokedIamSessions", ScsSessionRegistry.revokedCount());
    return ok(out, request);
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    return java.security.MessageDigest.isEqual(
        a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static Set<String> toStringSet(Object value) {
    Set<String> out = new HashSet<>();
    if (value instanceof Iterable<?> it) {
      for (Object o : it) {
        if (o != null) {
          String s = String.valueOf(o).trim();
          if (!s.isEmpty()) {
            out.add(s);
          }
        }
      }
    } else if (value instanceof String s) {
      for (String part : s.split(",")) {
        if (!part.trim().isEmpty()) {
          out.add(part.trim());
        }
      }
    }
    return out;
  }

  private ResponseEntity<Map<String, Object>> ok(Object data, HttpServletRequest request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", 0);
    body.put("message", "success");
    body.put("data", data);
    body.put("traceId", traceId(request));
    return ResponseEntity.ok(body);
  }

  /**
   * 统一错误响应。
   *
   * <p><b>失败必须返回非 2xx。</b>旧实现对所有业务失败都返回 HTTP 200 + 错误码，
   * 调用方（BFF、监控、探针）会把"撤销失败"当成成功——这是实测踩到的坑：
   * 中间层丢头后 revoke 返回 200 但 body 是 SSO_FORBIDDEN，
   * 端到端测试因此误判为"撤销成功但会话没失效"，排查耗时很久。
   */
  private ResponseEntity<Map<String, Object>> error(
      String code, String message, HttpServletRequest request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("message", message);
    body.put("data", null);
    body.put("traceId", traceId(request));
    return ResponseEntity.status(statusOf(code)).body(body);
  }

  private static HttpStatus statusOf(String code) {
    if (code == null) {
      return HttpStatus.BAD_REQUEST;
    }
    return switch (code) {
      case "SSO_NOT_CONFIGURED" -> HttpStatus.SERVICE_UNAVAILABLE;
      case "SSO_FORBIDDEN" -> HttpStatus.FORBIDDEN;
      case "SSO_BAD_REQUEST" -> HttpStatus.BAD_REQUEST;
      default -> HttpStatus.UNAUTHORIZED;
    };
  }

  private static String traceId(HttpServletRequest request) {
    String t = MDC.get(TraceIdFilter.MDC_KEY);
    if (t != null && !t.isBlank()) {
      return t;
    }
    Object attr = request.getAttribute(TraceIdFilter.MDC_KEY);
    return attr == null ? null : String.valueOf(attr);
  }

  public record ExchangeRequest(String ticket) {}

  public record RevokeRequest(String iamSessionId) {}
}
