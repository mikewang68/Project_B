package com.bdemo.sys.security;

import com.bdemo.sys.config.AppProperties;
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
 * SYS 不签发 token，仅用与 IAM 相同的 HS256 密钥本地校验 IAM 签发的 JWT。
 */
@Service
public class JwtService {

    private final SecretKey key;

    public JwtService(AppProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Instant expiresAt(String token) {
        return parse(token).getExpiration().toInstant();
    }
}
