package com.bproject.trust.identity;

import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/sso")
public class TrustSsoController {
  private final TrustSsoSessions sessions;
  public TrustSsoController(TrustSsoSessions sessions){this.sessions=sessions;}
  public record Exchange(String ticket){}
  public record Revoke(String iamSessionId,String subject,String audience,String reason){}
  @PostMapping("/exchange")
  public Map<String,Object> exchange(@RequestBody Exchange body,
      @RequestHeader(value="X-SSO-Service-Token",required=false)String credential,
      HttpServletRequest request,HttpServletResponse response){
    sessions.authorizeService(credential);
    var binding=sessions.exchange(body.ticket());
    var old=request.getSession(false);if(old!=null)old.invalidate();
    SecurityContextHolder.clearContext();sessions.bind(request.getSession(true),binding);
    new HttpSessionCsrfTokenRepository().saveToken(null,request,response);
    response.setHeader("Cache-Control","no-store");return Map.of("ok",true);
  }
  @PostMapping("/revoke")
  public Map<String,Object> revoke(@RequestBody Revoke body,
      @RequestHeader(value="X-SSO-Service-Token",required=false)String credential){
    sessions.authorizeService(credential);
    if(!"trust".equals(body.audience()))throw new com.bproject.trust.shared.web.ApiError(400,"目标系统不匹配");
    return Map.of("ok",true,"revoked",sessions.revoke(body.iamSessionId()));
  }
}
