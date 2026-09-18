package com.bdemo.iam.security;

import com.bdemo.iam.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发/校验（HS256）。claims 仅保存身份（userId、username），权限每次查库。
 * IAM 与 SYS 使用相同 JWT_SECRET，SYS 可本地校验 IAM 签发的 token。
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(AppProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofHours(Math.max(1, properties.getJwt().getExpireHours()));
    }

    public String generateToken(String userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("userId", userId)
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
