package com.bdemo.iam.sso;

import com.bdemo.iam.common.R;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth/sso/session")
public class SsoSessionController {
  private final SsoServiceIdentity services;
  private final SsoAuthorizationService authorization;
  @org.springframework.beans.factory.annotation.Autowired private com.bdemo.iam.auth.AuthService auth;
  @org.springframework.beans.factory.annotation.Autowired private com.bdemo.iam.security.LoginUserLoader users;
  @org.springframework.beans.factory.annotation.Autowired private SsoLogoutCoordinator logout;
  public SsoSessionController(SsoServiceIdentity services, SsoAuthorizationService authorization) {
    this.services = services; this.authorization = authorization;
  }
  public record Check(String subject, String sessionId, String audience) {}
  @PostMapping("/introspect")
  public R<Map<String, Object>> introspect(@RequestBody Check request,
      @RequestHeader(value = "X-SSO-Service-Token", required = false) String credential) {
    if (request.audience() == null || !services.authorize(request.audience().trim().toLowerCase(), credential))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "服务身份未获授权");
    try { return R.ok(authorization.current(request.subject(), request.sessionId(), request.audience())); }
    catch (SsoTicketService.SsoTicketException denied) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "会话或授权已失效");
    }
  }
  @PostMapping("/logout")
  public R<Map<String,Object>> logout(@RequestBody Check request,
      @RequestHeader(value="X-SSO-Service-Token",required=false)String credential,
      jakarta.servlet.http.HttpServletRequest http){
    if(request.audience()==null||!services.authorize(request.audience().trim().toLowerCase(),credential))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"服务身份未获授权");
    try{authorization.current(request.subject(),request.sessionId(),request.audience());}
    catch(SsoTicketService.SsoTicketException inactive){return R.ok(Map.of("active",false));}
    var user=users.load(request.subject());
    if(user==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    var revoked=auth.logout(user,http.getRemoteAddr());
    return R.ok(logout.logoutAll(user,request.sessionId(),revoked,http).toBody());
  }
}
