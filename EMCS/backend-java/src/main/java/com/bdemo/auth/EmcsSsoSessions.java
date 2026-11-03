package com.bdemo.auth;

import com.bdemo.system.SystemUserMapper;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmcsSsoSessions {
  public record Binding(String subject,String sid,long userId,String local)implements java.io.Serializable{}
  public record Current(AuthenticatedUser principal,Set<String> roles,Set<String> permissions,boolean readOnly,Set<String> centralPermissions){
    public Current(AuthenticatedUser principal,Set<String> roles,Set<String> permissions,boolean readOnly){this(principal,roles,permissions,readOnly,Set.of());}
  }
  private final String base,credential,file;private final boolean enabled;private final SystemUserMapper users;
  private final ObjectMapper json=new ObjectMapper();
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final Map<String,HttpSession> sessions=new ConcurrentHashMap<>();
  public EmcsSsoSessions(SystemUserMapper users,@Value("${emcs.sso.iam-base-url:}")String base,
      @Value("${emcs.sso.service-token:}")String credential,@Value("${emcs.sso.bindings-file:}")String file,
      @Value("${emcs.sso.enabled:false}")boolean enabled){this.users=users;this.base=base.replaceAll("/$","");this.credential=credential;this.file=file;this.enabled=enabled;}
  public void authorizeService(String supplied){
    if(!enabled||base.isBlank()||credential.length()<32||file.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    if(supplied==null||!MessageDigest.isEqual(credential.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
  private JsonNode call(String path,Object body){
    if(!enabled||base.isBlank()||credential.length()<32)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    try{
      var req=HttpRequest.newBuilder(URI.create(base+"/auth/sso/"+path)).timeout(Duration.ofSeconds(5))
          .header("Content-Type","application/json").header("X-SSO-Service-Token",credential)
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
      var response=http.send(req,HttpResponse.BodyHandlers.ofString());
      if(response.statusCode()==401||response.statusCode()==403)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
      if(response.statusCode()!=200)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
      var root=json.readTree(response.body());if(root.path("code").asInt(-1)!=0||!root.path("data").isObject())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
      return root.path("data");
    }catch(ResponseStatusException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);}
    catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);}
  }
  public void exchange(String ticket,HttpServletRequest request){
    if(ticket==null||ticket.isBlank()||ticket.length()>8192)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    var a=call("verify-and-consume",Map.of("ticket",ticket,"audience","emcs"));String sub=a.path("subject").asText();
    long userId;
    try{var b=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(file))).path(sub);if(!b.path("enabled").asBoolean(false))throw new IllegalArgumentException();userId=b.path("userId").asLong(-1);}
    catch(Exception e){throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
    var binding=new Binding(sub,a.path("sessionId").asText(),userId,a.path("localSubject").asText());resolve(binding,a);
    sessions.entrySet().removeIf(e->{try{e.getValue().getCreationTime();return false;}catch(IllegalStateException gone){return true;}});
    if(sessions.size()>=10000)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    var old=request.getSession(false);if(old!=null){sessions.remove(old.getId());old.invalidate();}var session=request.getSession(true);
    session.setAttribute("EMCS_SSO",binding);session.setAttribute("EMCS_CSRF",UUID.randomUUID().toString()+UUID.randomUUID());sessions.put(session.getId(),session);
  }
  public Current current(Binding binding){return resolve(binding,call("session/introspect",Map.of("subject",binding.subject(),"sessionId",binding.sid(),"audience","emcs")));}
  public boolean permits(Binding binding,Current current,String method,String path){
    if(Set.of("GET","HEAD").contains(method)&&Set.of("/getInfo","/getRouters","/auth/sso/csrf",
        "/transport/crypto/frontend-config","/transport/crypto/public-key").contains(path))return true;
    if(method.equals("POST")&&path.equals("/logout"))return true;
    if(current.readOnly()&&(!Set.of("GET","HEAD","OPTIONS").contains(method)||path.matches(".*/(export|download)(/.*)?")))return false;
    if(current.permissions().contains("*:*:*")&&!current.readOnly())return true;
    if(current.permissions().contains("EMCS_APPLICATION_ADMIN")&&!current.readOnly())return true;
    try{
      var rules=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(file))).path(binding.subject()).path("routes");
      for(var rule:rules)if(method.equals(rule.path("method").asText())&&path.matches(rule.path("pathPattern").asText())
          &&current.permissions().contains(rule.path("permission").asText()))return true;
    }catch(Exception ignored){}return false;
  }
  private Current resolve(Binding b,JsonNode a){
    // Re-read the only local mapping authority on every request, including admin fast paths.
    try {
      var mapping=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(file))).path(b.subject());
      if(!mapping.path("enabled").asBoolean(false)||!mapping.path("userId").canConvertToLong()
          ||mapping.path("userId").asLong()!=b.userId())throw new IllegalArgumentException();
    }catch(Exception unavailable){throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
    boolean appAllowed=false;
    if(a.path("allowedAppCodes").isArray())for(var app:a.path("allowedAppCodes"))if(app.isTextual()&&"EMCS".equals(app.asText()))appAllowed=true;
    if(!appAllowed||!b.sid().equals(a.path("sessionId").asText())||!a.path("permissions").isArray())
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);

    var user=users.findById(b.userId()).filter(u->"0".equals(u.status())&&"0".equals(u.delFlag())).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    if(b.subject().isBlank()||b.sid().isBlank()||!b.local().equals(user.userName())||!b.subject().equals(a.path("subject").asText())
        ||!b.local().equals(a.path("localSubject").asText())||!"emcs".equals(a.path("audience").asText())
        ||!a.path("readOnly").isBoolean())throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    for(String scope:List.of("company","org","domain","zone"))if(!a.path(scope).asText().isBlank())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Unsupported data scope");
    boolean readOnly=a.path("readOnly").asBoolean();Set<String> roles=new HashSet<>(users.findRoleKeys(b.userId()));
    if(readOnly){roles.remove("admin");roles.add("PLATFORM_AUDITOR_READONLY");}
    Set<String> permissions=new HashSet<>(users.findPermissions(b.userId()));
    boolean full=roles.contains("admin")&&a.path("superAdmin").asBoolean(false)&&!readOnly;
    Set<String> central=new HashSet<>();a.path("permissions").forEach(v->central.add(v.asText()));
    if(!full)permissions.removeIf(p->!central.contains(p)&&!central.contains("ems:"+p));
    if(readOnly)permissions.removeIf(p->!p.matches(".*:(list|read|view|query|detail)$"));
    if(full)permissions.add("*:*:*");
    if(!readOnly && roles.contains("lw_emcs_admin") && a.path("applicationAdmin").asBoolean(false)){permissions.add("EMCS_APPLICATION_ADMIN");roles.add("admin");}
    return new Current(new AuthenticatedUser(user.userId(),user.userName()),Set.copyOf(roles),Set.copyOf(permissions),readOnly,Set.copyOf(central));
  }
  public void logout(HttpSession session){if(session!=null&&session.getAttribute("EMCS_SSO") instanceof Binding b){call("session/logout",Map.of("subject",b.subject(),"sessionId",b.sid(),"audience","emcs"));sessions.remove(session.getId());try{session.invalidate();}catch(IllegalStateException ignored){}}}
  public int revoke(String sid){if(sid==null||sid.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST);int count=0;
    for(var e:sessions.entrySet())try{if(e.getValue().getAttribute("EMCS_SSO") instanceof Binding b&&sid.equals(b.sid())){e.getValue().invalidate();sessions.remove(e.getKey());count++;}}catch(IllegalStateException gone){sessions.remove(e.getKey());}return count;}
}
