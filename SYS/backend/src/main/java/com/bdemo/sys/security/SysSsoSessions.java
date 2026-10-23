package com.bdemo.sys.security;

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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class SysSsoSessions {
  public record Binding(String subject,String sid,String local)implements java.io.Serializable{}
  private final String base,credential,file;private final boolean enabled;private final LoginUserLoader users;
  private final ObjectMapper json=new ObjectMapper();
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final Map<String,HttpSession> sessions=new ConcurrentHashMap<>();
  public SysSsoSessions(LoginUserLoader users,@Value("${sys.sso.iam-base-url:}")String base,
      @Value("${sys.sso.service-token:}")String credential,@Value("${sys.sso.enabled:false}")boolean enabled,
      @Value("${sys.sso.bindings-file:}")String file){
    this.users=users;this.base=base.replaceAll("/$","");this.credential=credential;this.enabled=enabled;this.file=file;
  }
  public void authorizeService(String actual){
    if(!enabled||base.isBlank()||credential.length()<32||file.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    if(actual==null||!MessageDigest.isEqual(credential.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
  private JsonNode call(String path,Object body){
    if(!enabled||base.isBlank()||credential.length()<32||file.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    try{
      var request=HttpRequest.newBuilder(URI.create(base+"/auth/sso/"+path)).timeout(Duration.ofSeconds(5))
          .header("Content-Type","application/json").header("X-SSO-Service-Token",credential)
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
      var response=http.send(request,HttpResponse.BodyHandlers.ofString());
      if(response.statusCode()==401||response.statusCode()==403)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
      if(response.statusCode()!=200)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
      var root=json.readTree(response.body());
      if(root.path("code").asInt(-1)!=0||!root.path("data").isObject())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
      return root.path("data");
    }catch(ResponseStatusException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);}
    catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);}
  }
  public void exchange(String ticket,HttpServletRequest request){
    if(ticket==null||ticket.length()>8192)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    var a=call("verify-and-consume",Map.of("ticket",ticket,"audience","sys"));
    var b=new Binding(a.path("subject").asText(),a.path("sessionId").asText(),a.path("localSubject").asText());resolve(b,a);
    var old=request.getSession(false);if(old!=null)old.invalidate();var session=request.getSession(true);
    session.setAttribute("SYS_SSO",b);session.setAttribute("SYS_CSRF",UUID.randomUUID().toString()+UUID.randomUUID());
    if(sessions.size()>10000)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    sessions.put(session.getId(),session);
  }
  public LoginUser current(Binding b){return resolve(b,call("session/introspect",Map.of("subject",b.subject(),"sessionId",b.sid(),"audience","sys")));}
  private LoginUser resolve(Binding b,JsonNode a){
    String localUserId;
    try{
      var mapping=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(file))).path(b.subject());
      if(!mapping.path("enabled").asBoolean(false)||mapping.path("localUserId").asText().isBlank())throw new IllegalArgumentException();
      localUserId=mapping.path("localUserId").asText();
    }catch(Exception e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Explicit SYS subject mapping required");}
    var user=users.load(localUserId);
    if(user==null||b.sid().isBlank()||!"sys".equals(a.path("audience").asText())||!b.subject().equals(a.path("subject").asText())
        ||!b.local().equals(user.getUsername())||!b.local().equals(a.path("localSubject").asText())
        ||!a.path("readOnly").isBoolean()||!a.path("permissions").isArray())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    boolean readOnly=a.path("readOnly").asBoolean();user.setReadOnly(readOnly);
    user.setSuperAdmin(user.isSuperAdmin()&&a.path("superAdmin").asBoolean(false)&&!readOnly);
    if(!user.isSuperAdmin()){
      Set<String> central=new HashSet<>();a.path("permissions").forEach(v->central.add(v.asText()));
      Set<String> permissions=new HashSet<>(user.getPermissions());permissions.retainAll(central);user.setPermissions(permissions);
    }
    return user;
  }
  public void logout(Binding b){call("session/logout",Map.of("subject",b.subject(),"sessionId",b.sid(),"audience","sys"));}
  public int revoke(String sid){
    if(sid==null||sid.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST);int count=0;
    for(var e:sessions.entrySet())try{if(e.getValue().getAttribute("SYS_SSO") instanceof Binding b&&sid.equals(b.sid())){e.getValue().invalidate();sessions.remove(e.getKey());count++;}}
    catch(IllegalStateException gone){sessions.remove(e.getKey());}return count;
  }
}
