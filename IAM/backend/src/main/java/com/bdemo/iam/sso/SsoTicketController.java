package com.bdemo.iam.sso;

import com.bdemo.iam.common.R;
import com.bdemo.iam.security.JwtAuthFilter;
import com.bdemo.iam.security.LoginUser;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/**
 * SSO V1 票据端点。
 *
 * <p>安全边界：
 *
 * <ul>
 *   <li>{@code /ticket} 需要 IAM 登录态（Bearer），只能为**自己**签发票据，不能代他人签发。
 *   <li>受众必须在白名单内，且签发前校验该用户是否被授权访问该系统。
 *   <li>{@code /verify} 仅用于服务端到服务端（需共享票据密钥）；不向浏览器暴露。
 *   <li>票据不落在 URL 上，只在请求体/响应体里传递。
 * </ul>
 */
@RestController
@RequestMapping("/auth/sso")
public class SsoTicketController {

  private final SsoTicketService tickets;
  private final SsoSubjectGuard subjects;

  public SsoTicketController(SsoTicketService tickets, SsoSubjectGuard subjects) {
    this.tickets = tickets;
    this.subjects = subjects;
  }

  /**
   * 为当前登录用户签发一张发往目标系统的短票。
   *
   * <p>请求：{@code {"audience":"wms"}}
   */
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

    // 换票前置：该主体必须在目标系统有可用映射
    subjects.requireBound(user.getUserId(), user.getUsername(), audience);

    // 从 IAM 会话上下文取范围（不信任前端自报）
    Map<String, String> scope =
        subjects.scopeFor(user.getUserId(), user.getUsername(), audience, user.getScopeCompany(), user.getScopeZone());

    SsoTicketService.IssuedTicket issued =
        tickets.issue(user.getUserId(), user.getUsername(), sessionIdOf(http), audience, scope);

    Map<String, Object> body = new HashMap<>();
    body.put("ticket", issued.ticket());
    body.put("jti", issued.jti());
    body.put("audience", issued.audience());
    body.put("expiresAt", issued.expiresAt().toString());
    body.put("ttlSeconds", issued.ttlSeconds());
    return R.ok(body);
  }

  /** 诊断用：返回票据服务当前是否可用（不泄露密钥）。 */
  @GetMapping("/health")
  public R<Map<String, Object>> health() {
    Map<String, Object> body = new HashMap<>();
    body.put("enabled", true);
    body.put("issuer", tickets.issuer());
    body.put("ttlSeconds", tickets.ttlSeconds());
    body.put("allowedAudience", SsoTicketService.ALLOWED_AUDIENCE);
    return R.ok(body);
  }

  /**
   * 服务端到服务端校验 + 原子消费。供目标系统在无法本地验签时使用（V1 备用路径）。
   *
   * <p>调用方必须持有服务间凭据（由网关注入的 X-Gateway-Route + 网络隔离保护）。
   */
  @PostMapping("/verify-and-consume")
  public R<Map<String, Object>> verifyAndConsume(@RequestBody VerifyRequest request) {
    if (request == null || request.ticket() == null || request.audience() == null) {
      throw new SsoTicketService.SsoTicketException("缺少 ticket 或 audience");
    }
    Claims claims = tickets.verify(request.ticket(), request.audience());
    if (!tickets.consume(claims.getId(), request.consumer() == null ? "unknown" : request.consumer())) {
      throw new SsoTicketService.SsoTicketException("票据已被使用（重放被拒绝）");
    }
    Map<String, Object> body = new HashMap<>();
    body.put("subject", claims.getSubject());
    body.put("audience", String.valueOf(claims.getAudience()));
    body.put("username", claims.get("username", String.class));
    body.put("sessionId", claims.get("sid", String.class));
    body.put("company", claims.get("company", String.class));
    body.put("org", claims.get("org", String.class));
    body.put("domain", claims.get("domain", String.class));
    body.put("zone", claims.get("zone", String.class));
    return R.ok(body);
  }

  /** 注销联动：撤销该会话已签发但未消费的票据。 */
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

  /**
   * 票据相关失败统一映射为规范状态码，避免业务方把 500 当成系统故障。
   * 契约要求：验签/时效/重放类 → 401；无权限/未映射 → 403。
   */
  @ExceptionHandler(SsoTicketService.SsoTicketException.class)
  public ResponseEntity<Map<String, Object>> onTicketError(SsoTicketService.SsoTicketException e) {
    String msg = e.getMessage() == null ? "" : e.getMessage();
    boolean forbidden =
        msg.contains("未被授权") || msg.contains("映射已停用") || msg.contains("不支持的受众");
    HttpStatus status = forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
    Map<String, Object> body = new HashMap<>();
    body.put("code", forbidden ? 40301 : 40101);
    body.put("message", msg);
    body.put("data", null);
    return ResponseEntity.status(status).body(body);
  }

  private String sessionIdOf(HttpServletRequest http) {
    Object sid = http.getAttribute("iam.sessionId");
    return sid == null ? "" : String.valueOf(sid);
  }

  public record TicketRequest(String audience) {}

  public record VerifyRequest(String ticket, String audience, String consumer) {}
}
