package com.bdemo.auth;

import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/sso")
public class EmcsSsoController {
  private final EmcsSsoSessions sessions;public EmcsSsoController(EmcsSsoSessions sessions){this.sessions=sessions;}
  public record Exchange(String ticket){}public record Revoke(String iamSessionId){}
  @PostMapping("/exchange")public Map<String,Object> exchange(@RequestBody Exchange body,@RequestHeader(value="X-SSO-Service-Token",required=false)String credential,HttpServletRequest request){sessions.authorizeService(credential);sessions.exchange(body.ticket(),request);return Map.of("code",0,"ok",true);}
  @PostMapping("/revoke")public Map<String,Object> revoke(@RequestBody Revoke body,@RequestHeader(value="X-SSO-Service-Token",required=false)String credential){sessions.authorizeService(credential);return Map.of("code",0,"revoked",sessions.revoke(body.iamSessionId()));}
  @GetMapping("/csrf")public Map<String,Object> csrf(HttpServletRequest request){var s=request.getSession(false);Object token=s==null?null:s.getAttribute("EMCS_CSRF");return Map.of("code",200,"csrfToken",token==null?"":token);}
}
