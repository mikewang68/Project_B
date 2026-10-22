package com.bdemo.auth.sso;

import com.bdemo.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/sso")
public class EmcsSsoController {
    private final EmcsSsoService sso;
    private final EmcsSsoProperties properties;
    private final EmcsSsoSessionRepository sessions;
    public EmcsSsoController(EmcsSsoService sso,EmcsSsoProperties properties,EmcsSsoSessionRepository sessions){this.sso=sso;this.properties=properties;this.sessions=sessions;}
    @PostMapping("/exchange")
    public ResponseEntity<?> exchange(@RequestBody(required=false) ExchangeRequest body,
            @RequestHeader(value="X-SSO-Ticket",required=false)String header,HttpServletRequest request){
        if(request.getParameter("ticket")!=null)throw new BusinessException(400,"不得在URL传递票据");
        String fromBody=body==null?null:body.ticket();
        if(header!=null && fromBody!=null && !header.equals(fromBody))throw new BusinessException(400,"票据来源不一致");
        var issued=sso.exchange(header==null?fromBody:header);
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("token",issued.token());data.put("expiresAt",issued.expiresAt());
        data.put("userId",issued.access().user().userId());data.put("userName",issued.access().user().userName());
        data.put("readOnly",issued.access().effectiveReadOnly());data.put("scopeArea",issued.access().scopeArea());
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Pragma","no-cache").body(Map.of("code",0,"msg","success","data",data));
    }
    @PostMapping("/logout-notify")
    public Map<String,Object> logoutNotify(@RequestHeader(value="X-SSO-Service-Token",required=false)String token,
                                         @RequestBody(required=false)LogoutRequest body){
        if(!properties.serviceAuthorized(token))throw new BusinessException(403,"SSO服务身份无效");
        if(body==null || !"emcs".equals(body.audience()) || body.iamSessionId()==null || body.iamSessionId().isBlank()
                || body.iamSessionId().length()>128 || body.subject()==null)throw new BusinessException(400,"SSO注销通知无效");
        try{if(!UUID.fromString(body.subject()).toString().equalsIgnoreCase(body.subject()))throw new IllegalArgumentException();}
        catch(IllegalArgumentException e){throw new BusinessException(400,"SSO注销主体无效");}
        sessions.revokeIamSession(body.subject(),body.iamSessionId());
        return Map.of("code",0,"msg","success");
    }
    @GetMapping("/health")
    public Map<String,Object> health(){return Map.of("code",0,"data",Map.of("enabled",properties.usable(),"audience","emcs","contractVersion","1.1","nativeLoginPreserved",true));}
    public record ExchangeRequest(String ticket){}
    public record LogoutRequest(String iamSessionId,String subject,String audience,String reason){}
}
