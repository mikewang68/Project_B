package com.bdemo.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private final SecretKey key;
    private final Duration tokenDuration;

    public JwtService(
            @Value("${b-demo.auth.jwt-secret}") String secret,
            @Value("${b-demo.auth.token-minutes}") long tokenMinutes) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT_SECRET_KEY must contain at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.tokenDuration = Duration.ofMinutes(tokenMinutes);
    }

    public String create(long userId, String userName) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(Long.toString(userId))
                .claim("user_name", userName)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(tokenDuration)))
                .signWith(key)
                .compact();
    }

    public AuthenticatedUser parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return new AuthenticatedUser(Long.parseLong(claims.getSubject()), claims.get("user_name", String.class));
    }
}
