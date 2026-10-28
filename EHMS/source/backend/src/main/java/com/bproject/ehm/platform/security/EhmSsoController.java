package com.bproject.ehm.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("server") @ConditionalOnProperty(name="ehm.auth.mode",havingValue="iam")
@RequestMapping("/api/ehm/v1/auth/sso")
public class EhmSsoController {
    private final EhmSsoSessions sessions;
    public EhmSsoController(EhmSsoSessions sessions){this.sessions=sessions;}
    public record Exchange(String ticket){}public record Revoke(String iamSessionId){}
    @GetMapping("/health") public Map<String,Object> health(){return Map.of("code",0,"contractVersion","1.1","mode","iam","sessionStore","SINGLE_INSTANCE");}
    @PostMapping("/exchange") public Map<String,Object> exchange(@RequestBody Exchange body,HttpServletRequest request) throws Exception {
        sessions.requireService(request.getHeader("X-SSO-Service-Token"));
        var actor=sessions.exchange(body.ticket(),request);return Map.of("code",0,"data",Map.of("subject",actor.subject(),"role",actor.role()));
    }
    @PostMapping("/revoke") public Map<String,Object> revoke(@RequestBody Revoke body,@RequestHeader(value="X-SSO-Service-Token",required=false) String token){return Map.of("code",0,"data",Map.of("sessionsRevoked",sessions.revoke(body.iamSessionId(),token)));}
}
