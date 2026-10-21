package com.bdemo.iam.sso;

import com.bdemo.iam.config.AppProperties;
import com.bdemo.iam.security.mapper.SessionMapper;
import com.bdemo.iam.sso.mapper.SsoTicketMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * SSO V1 票据服务。
 *
 * <p>契约要点（见 docs/integration/SSO_AUTHZ_SLO_CONTRACT_V1.md）：
 *
 * <ul>
 *   <li>票据为 HS256 JWT，密钥独立于 IAM 登录 JWT（{@code SSO_TICKET_SECRET}）。
 *   <li>必含 {@code iss/aud/sub/jti/iat/nbf/exp}，且 {@code exp - iat <= 60s}。
 *   <li>一次性：签发时落库，消费由目标系统原子写入。
 *   <li>缺少密钥时启动即 fail-closed：不签发、不校验。
 * </ul>
 */
@Service
public class SsoTicketService {

  /** 允许的目标受众；与 AppCodeEnum 保持一致，但此处独立枚举以避免耦合。 */
  public static final List<String> ALLOWED_AUDIENCE =
      List.of("iam", "sys", "wms", "psms", "scs", "ehms", "emcs", "trust");

  static final long MAX_TTL_SECONDS = 60;
  static final long DEFAULT_TTL_SECONDS = 45;

  private final SsoTicketMapper tickets;
  private final SessionMapper sessions;
  private final ObjectMapper json;
  private final SecretKey key;
  private final String issuer;
  private final long ttlSeconds;

  public SsoTicketService(
      AppProperties properties, SsoTicketMapper tickets, SessionMapper sessions, ObjectMapper json) {
    AppProperties.Sso sso = properties.getSso();
    String secret = sso.getTicketSecret() == null ? "" : sso.getTicketSecret().trim();
    if (secret.length() < 32) {
      // fail-closed: 没有足够强的密钥就不允许签发或校验任何票据
      throw new IllegalStateException(
          "SSO_TICKET_SECRET 未配置或长度不足 32 字符；可信 SSO 已关闭（fail-closed）");
    }
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.issuer = sso.getIssuer() == null || sso.getIssuer().isBlank() ? "project-b-iam" : sso.getIssuer();
    long configured = sso.getTicketTtlSeconds();
    this.ttlSeconds = configured <= 0 ? DEFAULT_TTL_SECONDS : Math.min(configured, MAX_TTL_SECONDS);
    this.tickets = tickets;
    this.sessions = sessions;
    this.json = json;
  }

  /**
   * 为某个已认证会话签发一次性票据。
   *
   * @param subject IAM 用户主键（iam_user.id）
   * @param username 用户名，仅用于审计
   * @param sessionId 当前 IAM 会话 id（JWT 的 jti），用于注销联动
   * @param audience 目标系统
   * @param scope 公司/组织/域/区，可为空
   */
  public IssuedTicket issue(
      String subject,
      String username,
      String sessionId,
      String audience,
      Map<String, String> scope) {
    String aud = normalizeAudience(audience);
    if (subject == null || subject.isBlank()) {
      throw new IllegalArgumentException("subject 不能为空");
    }

    Instant now = Instant.now();
    Instant exp = now.plusSeconds(ttlSeconds);
    String jti = UUID.randomUUID().toString();

    String company = pick(scope, "company");
    String org = pick(scope, "org");
    String domain = pick(scope, "domain");
    String zone = pick(scope, "zone");

    String token =
        Jwts.builder()
            .id(jti)
            .issuer(issuer)
            .audience().add(aud).and()
            .subject(subject)
            .claim("username", username == null ? "" : username)
            .claim("sid", sessionId == null ? "" : sessionId)
            .claim("company", company)
            .claim("org", org)
            .claim("domain", domain)
            .claim("zone", zone)
            .issuedAt(Date.from(now))
            .notBefore(Date.from(now))
            .expiration(Date.from(exp))
            .signWith(key)
            .compact();

    tickets.insert(jti, subject, username, aud, sessionId, company, org, domain, zone, exp);
    return new IssuedTicket(token, jti, aud, exp, ttlSeconds);
  }

  /**
   * 校验票据（供 exchange 端点使用）。成功返回 claims；任何一项不满足即抛异常。
   *
   * <p>注意：本方法不做一次性消费；消费由调用方在同一事务内完成，以保证原子性。
   */
  public Claims verify(String token, String expectedAudience) {
    if (token == null || token.isBlank()) {
      throw new SsoTicketException("票据为空");
    }
    String aud = normalizeAudience(expectedAudience);

    Claims claims;
    try {
      claims =
          Jwts.parser()
              .verifyWith(key)
              .requireIssuer(issuer)
              .requireAudience(aud)
              .clockSkewSeconds(30)
              .build()
              .parseSignedClaims(token)
              .getPayload();
    } catch (io.jsonwebtoken.security.SecurityException
        | io.jsonwebtoken.UnsupportedJwtException
        | io.jsonwebtoken.MalformedJwtException
        | io.jsonwebtoken.ExpiredJwtException
        | io.jsonwebtoken.PrematureJwtException
        | io.jsonwebtoken.IncorrectClaimException
        | io.jsonwebtoken.MissingClaimException
        | IllegalArgumentException e) {
      throw new SsoTicketException("票据校验失败: " + e.getClass().getSimpleName());
    }

    if (claims.getId() == null || claims.getId().isBlank()) {
      throw new SsoTicketException("票据缺少 jti");
    }
    Long iat = claims.getIssuedAt() == null ? null : claims.getIssuedAt().getTime() / 1000;
    Long exp = claims.getExpiration() == null ? null : claims.getExpiration().getTime() / 1000;
    if (iat == null || exp == null) {
      throw new SsoTicketException("票据缺少 iat/exp");
    }
    if (exp - iat > MAX_TTL_SECONDS) {
      throw new SsoTicketException("票据有效期超过 " + MAX_TTL_SECONDS + " 秒");
    }
    if (claims.getSubject() == null || claims.getSubject().isBlank()) {
      throw new SsoTicketException("票据缺少 sub");
    }

    // 服务端记录必须存在且未撤销（防止伪造合同时序的票据）
    if (tickets.existsActive(claims.getId(), claims.getSubject(), aud) != 1) {
      throw new SsoTicketException("票据未登记或已撤销/过期");
    }
    return claims;
  }

  /** 原子消费：返回 false 表示已被使用（重放）。 */
  public boolean consume(String jti, String consumedBy) {
    return tickets.consume(jti, consumedBy) == 1;
  }

  /** 撤销该会话签发的、发往某系统的所有未消费票据。 */
  public void revokeBySession(String sessionId) {
    if (sessionId != null && !sessionId.isBlank()) {
      tickets.revokeBySession(sessionId);
    }
  }

  /** 撤销某主体在某受众上的未消费票据。 */
  public void revokeBySubject(String subject, String audience) {
    tickets.revokeBySubject(subject, normalizeAudience(audience));
  }

  public String issuer() {
    return issuer;
  }

  public long ttlSeconds() {
    return ttlSeconds;
  }

  /** 读取票据 payload 但不验签——仅用于诊断，绝不可用于授权。 */
  String decodeWithoutVerify(String token) {
    String[] parts = token.split("\\.");
    if (parts.length < 2) return "";
    return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
  }

  private static String normalizeAudience(String audience) {
    String aud = audience == null ? "" : audience.trim().toLowerCase();
    if (!ALLOWED_AUDIENCE.contains(aud)) {
      throw new IllegalArgumentException("不支持的受众: " + audience);
    }
    return aud;
  }

  private static String pick(Map<String, String> scope, String key) {
    if (scope == null) return "";
    String v = scope.get(key);
    return v == null ? "" : v.trim();
  }

  /** 诊断用：把 claims 转成只含白名单字段的 map（不含任何凭据）。 */
  Map<String, Object> summary(Claims claims) {
    return Map.of(
        "sub", claims.getSubject(),
        "aud", String.valueOf(claims.getAudience()),
        "jti", claims.getId(),
        "iss", claims.getIssuer(),
        "exp", claims.getExpiration() == null ? 0L : claims.getExpiration().getTime() / 1000);
  }

  /** 供 JsonNode 场景使用（如未来接 introspection）。 */
  JsonNode asJson(Claims claims) {
    return json.valueToTree(
        Map.of(
            "sub", claims.getSubject(),
            "aud", String.valueOf(claims.getAudience()),
            "jti", claims.getId(),
            "iat", claims.getIssuedAt() == null ? 0L : claims.getIssuedAt().getTime() / 1000,
            "exp", claims.getExpiration() == null ? 0L : claims.getExpiration().getTime() / 1000));
  }

  /** 对外返回的签发票据视图。 */
  public record IssuedTicket(String ticket, String jti, String audience, Instant expiresAt, long ttlSeconds) {}

  /** 票据相关的可预期失败。 */
  public static class SsoTicketException extends RuntimeException {
    public SsoTicketException(String message) {
      super(message);
    }
  }

  static Duration maxTtl() {
    return Duration.ofSeconds(MAX_TTL_SECONDS);
  }
}
