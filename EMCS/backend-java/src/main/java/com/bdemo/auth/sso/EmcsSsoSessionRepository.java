package com.bdemo.auth.sso;

import com.bdemo.auth.AuthenticatedUser;
import com.bdemo.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persistent opaque bearer sessions; never store bearer credentials or an HTTP SecurityContext. */
@Repository
public class EmcsSsoSessionRepository {
    public static final String PREFIX="emcs_sso_";
    private final JdbcTemplate jdbc;
    private final EmcsSsoBindingRepository bindings;
    private final EmcsSsoProperties properties;
    private final SecureRandom random=new SecureRandom();
    public EmcsSsoSessionRepository(JdbcTemplate jdbc,EmcsSsoBindingRepository bindings,EmcsSsoProperties properties){
        this.jdbc=jdbc;this.bindings=bindings;this.properties=properties;
    }
    public record Issued(String token,long expiresAt,EmcsSsoAccess access){}
    @Transactional
    public Issued issue(EmcsSsoService.Assertion assertion){
        bindings.lock(assertion.subject());
        var binding=bindings.findActive(assertion.subject()).orElseThrow(()->new BusinessException(403,"SSO主体未绑定或账号不可用"));
        if(!binding.localUserName().equals(assertion.localSubject()) || !binding.company().equals(assertion.company())
                || !binding.org().equals(assertion.org()) || !binding.domain().equals(assertion.domain()) || !binding.zone().equals(assertion.zone()))
            throw new BusinessException(403,"SSO主体或数据范围与本地登记不一致");
        var states=jdbc.queryForList("SELECT revoked FROM emcs_sso_iam_session WHERE iam_subject=? AND iam_session_id=? FOR UPDATE",assertion.subject(),assertion.sessionId());
        if(states.isEmpty()) jdbc.update("INSERT INTO emcs_sso_iam_session(iam_subject,iam_session_id,revoked) VALUES(?,?,0)",assertion.subject(),assertion.sessionId());
        else if(((Number)states.get(0).get("revoked")).intValue()!=0) throw new BusinessException(401,"IAM会话已注销");
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String token=PREFIX+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiry=Instant.now().plusSeconds(properties.getSessionMinutes()*60);
        jdbc.update("""
                INSERT INTO emcs_sso_session(token_hash,iam_subject,iam_session_id,binding_fingerprint,expires_at_epoch)
                VALUES(?,?,?,?,?)
                """,hash(token),assertion.subject(),assertion.sessionId(),binding.fingerprint(),expiry.getEpochSecond());
        return new Issued(token,expiry.getEpochSecond(),new EmcsSsoAccess(new AuthenticatedUser(binding.localUserId(),binding.localUserName()),
                assertion.subject(),assertion.sessionId(),binding.readOnly(),binding.scopeArea()));
    }
    public Optional<EmcsSsoAccess> findActive(String token){
        if(!properties.usable() || token==null || !token.matches("emcs_sso_[A-Za-z0-9_-]{43}"))return Optional.empty();
        var rows=jdbc.queryForList("""
                SELECT s.iam_subject,s.iam_session_id,s.binding_fingerprint FROM emcs_sso_session s
                JOIN emcs_sso_iam_session i ON i.iam_subject=s.iam_subject AND i.iam_session_id=s.iam_session_id
                WHERE s.token_hash=? AND s.revoked=0 AND i.revoked=0 AND s.expires_at_epoch>?
                """,hash(token),Instant.now().getEpochSecond());
        if(rows.isEmpty())return Optional.empty();
        var row=rows.get(0);String subject=row.get("iam_subject").toString();
        var binding=bindings.findActive(subject).filter(b->b.fingerprint().equals(row.get("binding_fingerprint")));
        if(binding.isEmpty()) { revokeToken(token);return Optional.empty(); }
        return binding.map(b->new EmcsSsoAccess(new AuthenticatedUser(b.localUserId(),b.localUserName()),subject,row.get("iam_session_id").toString(),b.readOnly(),b.scopeArea()));
    }
    public void revokeToken(String token){
        if(token!=null && token.startsWith(PREFIX))jdbc.update("UPDATE emcs_sso_session SET revoked=1 WHERE token_hash=?",hash(token));
    }
    @Transactional
    public void revokeIamSession(String subject,String sessionId){
        bindings.lock(subject);
        var states=jdbc.queryForList("SELECT revoked FROM emcs_sso_iam_session WHERE iam_subject=? AND iam_session_id=? FOR UPDATE",subject,sessionId);
        // Keep a tombstone even when logout arrives before exchange finishes.
        if(states.isEmpty())jdbc.update("INSERT INTO emcs_sso_iam_session(iam_subject,iam_session_id,revoked) VALUES(?,?,1)",subject,sessionId);
        else jdbc.update("UPDATE emcs_sso_iam_session SET revoked=1 WHERE iam_subject=? AND iam_session_id=?",subject,sessionId);
        jdbc.update("UPDATE emcs_sso_session SET revoked=1 WHERE iam_subject=? AND iam_session_id=?",subject,sessionId);
    }
    private String hash(String token){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
