package com.bdemo.sys.auth;

import com.bdemo.sys.common.R;
import com.bdemo.sys.security.SysSsoSessions;
import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/sso")
public class SysSsoController {
  private final SysSsoSessions sessions;
  public SysSsoController(SysSsoSessions sessions){this.sessions=sessions;}
  public record Exchange(String ticket){} public record Revoke(String iamSessionId){}
  @PostMapping("/exchange")public R<Map<String,Object>> exchange(@RequestBody Exchange body,
      @RequestHeader(value="X-SSO-Service-Token",required=false)String credential,HttpServletRequest request){
    sessions.authorizeService(credential);sessions.exchange(body.ticket(),request);return R.ok(Map.of("ok",true));
  }
  @PostMapping("/revoke")public R<Map<String,Object>> revoke(@RequestBody Revoke body,
      @RequestHeader(value="X-SSO-Service-Token",required=false)String credential){
    sessions.authorizeService(credential);return R.ok(Map.of("revoked",sessions.revoke(body.iamSessionId())));
  }
  @GetMapping("/csrf")public R<Map<String,Object>> csrf(HttpServletRequest request){
    var session=request.getSession(false);Object csrf=session==null?null:session.getAttribute("SYS_CSRF");
    return R.ok(Map.of("csrfToken",csrf==null?"":csrf));
  }
  @PostMapping("/logout")public R<Map<String,Object>> logout(HttpServletRequest request){
    var session=request.getSession(false);
    if(session!=null&&session.getAttribute("SYS_SSO") instanceof SysSsoSessions.Binding binding){sessions.logout(binding);try{session.invalidate();}catch(IllegalStateException ignored){}}
    return R.ok(Map.of("ok",true));
  }
}
