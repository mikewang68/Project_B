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
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.springframework.jdbc.core.JdbcTemplate revocationDb;
    private final java.util.Map<String,Instant> localRevocations=new java.util.concurrent.ConcurrentHashMap<>();

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
                .id(java.util.UUID.randomUUID().toString())
                .subject(Long.toString(userId))
                .claim("user_name", userName)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(tokenDuration)))
                .signWith(key)
                .compact();
    }

    public AuthenticatedUser parse(String token) {
        String hash=hash(token);
        if(revocationDb!=null){
            Integer count=revocationDb.queryForObject("SELECT COUNT(*) FROM sys_token_revocation WHERE token_sha256=? AND expires_at>CURRENT_TIMESTAMP",Integer.class,hash);
            if(count!=null&&count>0)throw new IllegalArgumentException("Session revoked");
        }else if(localRevocations.getOrDefault(hash,Instant.MIN).isAfter(Instant.now()))throw new IllegalArgumentException("Session revoked");
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return new AuthenticatedUser(Long.parseLong(claims.getSubject()), claims.get("user_name", String.class));
    }
    public void revoke(String token){
        Claims claims=Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        Instant expires=claims.getExpiration().toInstant();String hash=hash(token);
        if(revocationDb!=null){
            int changed=revocationDb.update("UPDATE sys_token_revocation SET expires_at=? WHERE token_sha256=?",java.sql.Timestamp.from(expires),hash);
            if(changed==0)try{revocationDb.update("INSERT INTO sys_token_revocation(token_sha256,expires_at) VALUES(?,?)",hash,java.sql.Timestamp.from(expires));}
            catch(org.springframework.dao.DuplicateKeyException concurrent){/* Same token already revoked. */}
        }else localRevocations.put(hash,expires);
    }
    private static String hash(String token){
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
