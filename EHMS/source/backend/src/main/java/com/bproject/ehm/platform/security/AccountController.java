package com.bproject.ehm.platform.security;

import com.bproject.ehm.platform.audit.adapter.in.web.IamAuditIdentityFilter;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController @Profile("server") @RequestMapping("/api/ehm/v1/auth")
public class AccountController {
    @org.springframework.beans.factory.annotation.Autowired private ObjectProvider<EhmSsoSessions> ssoSessions;
    private final ObjectProvider<LocalAccountService> accounts;private final String mode,cookie;private final boolean secure;private final long hours;
    public AccountController(ObjectProvider<LocalAccountService> accounts,@Value("${ehm.auth.mode:disabled}") String mode,@Value("${ehm.auth.cookie-name:EHM_SESSION}") String cookie,@Value("${ehm.auth.cookie-secure:true}") boolean secure,@Value("${ehm.auth.session-hours:8}") long hours){this.accounts=accounts;this.mode=mode;this.cookie=cookie;this.secure=secure;this.hours=hours;}
    private LocalAccountService local(){var target=accounts.getIfAvailable();if(target==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"当前使用统一IAM身份，请从统一认证入口登录或管理账号");return target;}
    public record Login(String username,String password){}
    @PostMapping(value="/login",consumes="application/json") public ResponseEntity<?> login(@RequestBody Login body,HttpServletRequest request){
        var result=local().login(body.username(),body.password(),request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE,sessionCookie(result.token(),hours*3600)).body(session(result.user()));
    }
    @GetMapping("/me") public ResponseEntity<?> me(HttpServletRequest request){
        var actor=IamAuditIdentityFilter.actor(request);
        if(actor instanceof LocalUser user)return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(session(user));
        if(mode.equals("disabled"))return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("enabled",false,"mode",mode));
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
        String csrf=request.getSession(false)==null?"":Objects.toString(request.getSession(false).getAttribute(EhmSsoSessions.CSRF),"");
        if(actor instanceof EhmSsoSessions.Actor nativeActor)return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            Map.of("enabled",true,"mode","iam","csrfToken",csrf,"projectIds",nativeActor.projects(),"permissions",nativeActor.permissions(),"readOnly",nativeActor.readOnly(),"globalRead",nativeActor.globalRead(),
                "projectId",Objects.toString(request.getAttribute("EHM_PROJECT_ID"),""),"user",
                Map.of("id",nativeActor.subject(),"username",nativeActor.localSubject(),"displayName",nativeActor.localSubject(),"role",nativeActor.admin()?"SUPER_ADMIN":"USER")));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("enabled",true,"mode","iam","csrfToken",csrf,"user",Map.of("id",actor.getName(),"username",actor.getName(),"displayName",actor.getName(),"role",BusinessPermissionFilter.admin(request)?"SUPER_ADMIN":"USER")));
    }
    @PostMapping("/logout") public ResponseEntity<Void> logout(HttpServletRequest request){
        if(mode.equals("iam")){var service=ssoSessions.getIfAvailable();if(service==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);service.logout(request.getSession(false));return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
        local().logout((String)request.getAttribute(LocalSessionFilter.SESSION_TOKEN),request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE,sessionCookie("",0)).build();
    }
    public record UserCreate(String username,String displayName,String password,String role){}
    public record UserUpdate(String displayName,String role,Boolean enabled){}
    public record PasswordReset(String password){}
    public record PasswordChange(String currentPassword,String newPassword){}
    @GetMapping("/users") public List<Map<String,Object>> list(HttpServletRequest request){requireAdmin(request);return local().list();}
    @PostMapping(value="/users",consumes="application/json") public ResponseEntity<?> create(@RequestBody UserCreate body,HttpServletRequest request){requireAdmin(request);return ResponseEntity.status(201).body(local().create(body.username(),body.displayName(),body.password(),body.role(),request));}
    @PutMapping(value="/users/{id}",consumes="application/json") public Map<String,Object> update(@PathVariable String id,@RequestBody UserUpdate body,HttpServletRequest request){requireAdmin(request);if(body.enabled()==null)throw new IllegalArgumentException("Enabled is required");return local().update(id,body.displayName(),body.role(),body.enabled(),request);}
    @PostMapping(value="/users/{id}/password",consumes="application/json") public ResponseEntity<Void> reset(@PathVariable String id,@RequestBody PasswordReset body,HttpServletRequest request){requireAdmin(request);local().reset(id,body.password(),request);return ResponseEntity.noContent().build();}
    @PostMapping(value="/password",consumes="application/json") public ResponseEntity<Void> password(@RequestBody PasswordChange body,HttpServletRequest request){
        if(!(IamAuditIdentityFilter.actor(request) instanceof LocalUser actor))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"此入口仅修改本地账号密码");
        local().changePassword(actor,body.currentPassword(),body.newPassword(),request);return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,sessionCookie("",0)).build();
    }
    private void requireAdmin(HttpServletRequest request){if(!BusinessPermissionFilter.admin(request))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅超级管理员可管理账号");}
    private Map<String,Object> session(LocalUser user){return Map.of("enabled",true,"mode","local","user",user.view(),"csrfToken",user.csrfToken(),"sessionHours",hours,"capabilities",Map.of("read",true,"write",user.admin(),"manageUsers",user.admin()));}
    private String sessionCookie(String token,long seconds){return ResponseCookie.from(cookie,token).httpOnly(true).secure(secure).sameSite("Lax").path("/api/ehm/v1").maxAge(seconds).build().toString();}
}
