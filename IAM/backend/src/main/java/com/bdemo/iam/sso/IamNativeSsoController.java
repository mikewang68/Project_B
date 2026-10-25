package com.bdemo.iam.sso;

import com.bdemo.iam.common.R;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth/sso")
public class IamNativeSsoController {
  private final SsoServiceIdentity services;
  private final SsoTicketService tickets;
  private final SsoAuthorizationService authorization;
  private final IamNativeSessions sessions;
  public IamNativeSsoController(SsoServiceIdentity services,SsoTicketService tickets,SsoAuthorizationService authorization,IamNativeSessions sessions){
    this.services=services;this.tickets=tickets;this.authorization=authorization;this.sessions=sessions;
  }
  public record Exchange(String ticket){}
  @PostMapping("/exchange")
  public R<Map<String,Object>> exchange(@RequestBody Exchange body,
      @RequestHeader(value="X-SSO-Service-Token",required=false)String credential,HttpServletRequest request){
    if(!services.authorize("iam",credential))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    var claims=tickets.verify(body.ticket(),"iam");
    var data=authorization.current(claims.getSubject(),claims.get("sid",String.class),"iam");
    if(!data.get("username").equals(data.get("localSubject")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"主体映射不一致");
    if(!tickets.consume(claims.getId(),"iam"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    var old=request.getSession(false);if(old!=null)old.invalidate();
    var session=request.getSession(true);
    session.setAttribute("IAM_NATIVE_SUBJECT",claims.getSubject());session.setAttribute("IAM_NATIVE_SID",claims.get("sid",String.class));
    byte[] nonce=new byte[32];new SecureRandom().nextBytes(nonce);
    session.setAttribute("IAM_NATIVE_CSRF",Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
    sessions.register(session);
    return R.ok(Map.of("ok",true));
  }
  public record Revoke(String iamSessionId,String subject,String audience,String reason){}
  @PostMapping("/native/revoke")
  public R<Map<String,Object>> revoke(@RequestBody Revoke body,
      @RequestHeader(value="X-SSO-Service-Token",required=false)String credential){
    if(!services.authorize("iam",credential))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if(!"iam".equals(body.audience()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"目标系统不匹配");
    return R.ok(Map.of("revoked",sessions.revoke(body.iamSessionId())));
  }
  @GetMapping("/csrf")
  public R<Map<String,Object>> csrf(HttpServletRequest request){
    var session=request.getSession(false);
    Object value=session==null?null:session.getAttribute("IAM_NATIVE_CSRF");
    return R.ok(Map.of("csrfToken",value==null?"":value));
  }
}
