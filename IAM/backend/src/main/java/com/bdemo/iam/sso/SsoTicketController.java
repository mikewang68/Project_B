package com.bdemo.iam.sso;

import com.bdemo.iam.common.R;
import com.bdemo.iam.security.JwtAuthFilter;
import com.bdemo.iam.security.LoginUser;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SSO 票据端点（V1.1）。
 *
 * <p>V1.1 相对 V1 的两处语义变更：
 *
 * <ol>
 *   <li><b>唯一消费点</b>：集中式。目标后端通过 {@code /verify-and-consume} 换取身份断言，
 *       由 IAM 完成 jti 原子消费；目标系统不再自行接受未验证的前端 claims，
 *       也不得再维护第二份消费记录（否则正常票据会被二次判重）。
 *   <li><b>服务身份</b>：消费端点要求 {@code X-SSO-Service-Token}，其登记受众必须等于票据
 *       {@code aud}。浏览器持有 IAM 用户 token 不能调用该端点。
 * </ol>
 *
 * <p>签发端点 {@code /ticket} 仍要求 IAM 登录态，且只能为**自己**签发。
 */
@RestController
@RequestMapping("/auth/sso")
public class SsoTicketController {

  private final SsoTicketService tickets;
  private final SsoSubjectGuard subjects;
  private final SsoServiceIdentity services;

  @org.springframework.beans.factory.annotation.Autowired
  private SsoAuthorizationService authorization;

  public SsoTicketController(
      SsoTicketService tickets, SsoSubjectGuard subjects, SsoServiceIdentity services) {
    this.tickets = tickets;
    this.subjects = subjects;
    this.services = services;
  }

  /** 为当前登录用户签发一张发往目标系统的短票。请求体：{@code {"audience":"wms"}} */
  @PostMapping("/ticket")
  public R<Map<String, Object>> issue(
      @RequestBody(required = false) TicketRequest request, HttpServletRequest http) {
    LoginUser user = JwtAuthFilter.currentUser();
    if (user == null) {
      throw new SsoTicketService.SsoTicketException("未登录");
    }
    String audience = request == null ? null : request.audience();
    if (audience == null || audience.isBlank()) {
      throw new SsoTicketService.SsoTicketException("缺少 audience");
    }

    // V1.1：必须有显式主体映射，无回退
    subjects.requireBound(user.getUserId(), user.getUsername(), audience);
    Map<String, String> scope = subjects.scopeFor(user.getUserId(), audience);

    SsoTicketService.IssuedTicket issued =
        tickets.issue(user.getUserId(), user.getUsername(), sessionIdOf(http), audience, scope);

    Map<String, Object> body = new HashMap<>();
    body.put("ticket", issued.ticket());
    body.put("jti", issued.jti());
    body.put("audience", issued.audience());
    body.put("expiresAt", issued.expiresAt().toString());
    body.put("ttlSeconds", issued.ttlSeconds());
    body.put("contractVersion", "1.1");
    return R.ok(body);
  }

  /** 诊断端点：不泄露密钥与凭据。 */
  @GetMapping("/health")
  public R<Map<String, Object>> health() {
    Map<String, Object> body = new HashMap<>();
    body.put("enabled", true);
    body.put("contractVersion", "1.1");
    body.put("issuer", tickets.issuer());
    body.put("ttlSeconds", tickets.ttlSeconds());
    body.put("allowedAudience", SsoTicketService.ALLOWED_AUDIENCE);
    body.put("serviceIdentitiesConfigured", services.configured());
    body.put("serviceAudiences", services.audiences());
    body.put("consumptionPoint", "CENTRALIZED_IAM");
    return R.ok(body);
  }

  /**
   * 集中式验票 + 一次性消费。仅限持有对应受众服务凭据的目标后端调用。
   *
   * <p>顺序：服务身份 → 票据校验 → 原子消费。服务身份校验放在最前，
   * 避免未授权方探测票据有效性。
   */
  @PostMapping("/verify-and-consume")
  public R<Map<String, Object>> verifyAndConsume(
      @RequestBody(required = false) VerifyRequest request,
      @RequestHeader(value = "X-SSO-Service-Token", required = false) String serviceToken) {
    if (request == null || request.ticket() == null || request.audience() == null) {
      throw new SsoTicketService.SsoTicketException("缺少 ticket 或 audience");
    }
    String aud = request.audience().trim().toLowerCase();

    if (!services.authorize(aud, serviceToken)) {
      throw new SsoServiceDeniedException("服务身份无效或与票据受众不匹配");
    }

    Claims claims = tickets.verify(request.ticket(), aud);
    Map<String, Object> currentAuthorization =
        authorization.current(claims.getSubject(), claims.get("sid", String.class), aud);
    String consumer = request.consumer() == null ? aud : request.consumer();
    if (!tickets.consume(claims.getId(), consumer)) {
      throw new SsoTicketService.SsoTicketException("票据已被使用（重放被拒绝）");
    }

    Map<String, Object> body = new HashMap<>();
    body.put("subject", claims.getSubject());
    body.put("username", claims.get("username", String.class));
    body.put("audience", String.valueOf(claims.getAudience()));
    body.put("sessionId", claims.get("sid", String.class));
    body.put("company", claims.get("company", String.class));
    body.put("org", claims.get("org", String.class));
    body.put("domain", claims.get("domain", String.class));
    body.put("zone", claims.get("zone", String.class));
    body.put("localSubject", subjects.localSubject(claims.getSubject(), aud));
    body.putAll(currentAuthorization);
    return R.ok(body);
  }

  /** 注销联动：撤销当前会话已签发但未消费的票据。 */
  @PostMapping("/revoke")
  public R<Map<String, Object>> revoke(HttpServletRequest http) {
    LoginUser user = JwtAuthFilter.currentUser();
    if (user == null) {
      throw new SsoTicketService.SsoTicketException("未登录");
    }
    String sid = sessionIdOf(http);
    tickets.revokeBySession(sid);
    Map<String, Object> body = new HashMap<>();
    body.put("revokedSession", sid);
    return R.ok(body);
  }

  /** 票据类失败：401（验签/时效/重放）或 403（无权限/无映射/受众非法）。 */
  @ExceptionHandler(SsoTicketService.SsoTicketException.class)
  public ResponseEntity<Map<String, Object>> onTicketError(SsoTicketService.SsoTicketException e) {
    String msg = e.getMessage() == null ? "" : e.getMessage();
    boolean forbidden =
        msg.contains("未被授权")
            || msg.contains("映射已停用")
            || msg.contains("无显式映射")
            || msg.contains("本地主体为空")
            || msg.contains("不支持的受众");
    return errorResponse(
        forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED, forbidden ? 40301 : 40101, msg);
  }

  /** 服务身份失败：403，且不暴露票据是否有效。 */
  @ExceptionHandler(SsoServiceDeniedException.class)
  public ResponseEntity<Map<String, Object>> onServiceDenied(SsoServiceDeniedException e) {
    return errorResponse(HttpStatus.FORBIDDEN, 40302, e.getMessage());
  }

  private ResponseEntity<Map<String, Object>> errorResponse(
      HttpStatus status, int code, String message) {
    Map<String, Object> body = new HashMap<>();
    body.put("code", code);
    body.put("message", message);
    body.put("data", null);
    return ResponseEntity.status(status).body(body);
  }

  private String sessionIdOf(HttpServletRequest http) {
    Object sid = http.getAttribute("iam.sessionId");
    return sid == null ? "" : String.valueOf(sid);
  }

  public record TicketRequest(String audience) {}

  public record VerifyRequest(String ticket, String audience, String consumer) {}

  /** 服务身份校验失败（独立类型，便于映射为 40302 而不是票据类 401）。 */
  public static class SsoServiceDeniedException extends RuntimeException {
    public SsoServiceDeniedException(String message) {
      super(message);
    }
  }
}
