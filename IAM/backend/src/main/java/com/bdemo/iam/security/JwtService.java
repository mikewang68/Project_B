package com.bdemo.iam.security;

import com.bdemo.iam.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * JWT 签发/校验（HS256）。claims 仅保存身份（userId、username），权限每次查库。 IAM 独立密钥；服务调用 IAM API
 * 检查实时权限，令牌必须对应有效的持久会话。
 */
@Service
public class JwtService {

  private final com.bdemo.iam.security.mapper.SessionMapper sessions;
  private final SecretKey key;
  private final Duration ttl;

  public JwtService(
      AppProperties properties, com.bdemo.iam.security.mapper.SessionMapper sessions) {
    this.sessions = sessions;
    this.key = Keys.hmacShaKeyFor(properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    this.ttl = Duration.ofHours(Math.max(1, properties.getJwt().getExpireHours()));
  }

  public String generateToken(String userId, String username) {
    Instant now = Instant.now();
    String sessionId = java.util.UUID.randomUUID().toString();
    sessions.insert(sessionId, userId, now.plus(ttl));
    return Jwts.builder()
        .id(sessionId)
        .subject(userId)
        .claim("userId", userId)
        .claim("username", username)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(ttl)))
        .signWith(key)
        .compact();
  }

  public Claims parse(String token) {
    Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    if (claims.getId() == null || sessions.active(claims.getId(), claims.getSubject()) != 1)
      throw new io.jsonwebtoken.JwtException("会话已失效");
    return claims;
  }
}
