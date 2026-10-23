package com.bproject.ehm.platform.security;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Single-instance IAM session adapter. Bindings are explicit, private and local to EHM. */
@Service @Profile("server") @ConditionalOnProperty(name="ehm.auth.mode",havingValue="iam")
public class EhmSsoSessions {
    public static final String ACTOR=EhmSsoSessions.class.getName()+".actor";
    public static final String CSRF=EhmSsoSessions.class.getName()+".csrf";
    private final String base,token;private final ObjectMapper mapper;private final JsonNode bindings;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String,HttpSession> sessions=new ConcurrentHashMap<>();
    private final Set<String> revoked=ConcurrentHashMap.newKeySet();
    public record Actor(String subject,String localSubject,String role,String iamSid,boolean readOnly,Set<String> projects) implements java.security.Principal {
        public String getName(){return subject;}public boolean admin(){return role.equals("SUPER_ADMIN")&&!readOnly;}
    }
    public EhmSsoSessions(ObjectMapper mapper,@Value("${ehm.sso.iam-base-url:}") String base,
        @Value("${ehm.sso.service-token:}") String token,@Value("${ehm.sso.bindings-file:}") String file) throws Exception {
        this.mapper=mapper;this.base=base.replaceAll("/+$","");this.token=token;
        if(base.isBlank()||token.length()<24||file.isBlank())throw new IllegalArgumentException("IAM SSO requires private service identity and explicit bindings");
        bindings=mapper.readTree(Files.readString(Path.of(file)));
        if(!bindings.isObject())throw new IllegalArgumentException("Invalid EHM SSO bindings");
    }
    public Actor exchange(String ticket,HttpServletRequest request) throws Exception {
        if(!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(request.getRemoteAddr()))throw fail(403,"SSO_SOURCE_DENIED");
        if(ticket==null||ticket.isBlank()||ticket.length()>4096)throw fail(401,"SSO_TICKET_REQUIRED");
        var response=http.send(HttpRequest.newBuilder(URI.create(base+"/auth/sso/verify-and-consume"))
            .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").header("X-SSO-Service-Token",token)
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("ticket",ticket,"audience","ehms","consumer","ehms")))).build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw fail(401,"SSO_TICKET_REJECTED");
        var root=mapper.readTree(response.body());var data=root.path("data");
        if(!root.path("code").isIntegralNumber()||root.path("code").intValue()!=0)throw fail(401,"SSO_TICKET_REJECTED");
        String sub=data.path("subject").asText(),sid=data.path("sessionId").asText(),local=data.path("localSubject").asText();
        if(!sub.matches("[a-fA-F0-9]{32}"))try{UUID.fromString(sub);}catch(IllegalArgumentException invalid){throw fail(401,"SSO_BAD_SUBJECT");}
        var binding=bindings.path(sub);String role=binding.path("role").asText();
        if(sid.isBlank()||revoked.contains(sid))throw fail(401,"SSO_REVOKED");
        if(!binding.isObject()||local.isBlank()||!binding.path("localSubject").asText().equals(local)
            ||!Set.of("USER","SUPER_ADMIN").contains(role))throw fail(403,"SSO_NO_LOCAL_MAPPING");
        Actor verified=resolve(data,sub,sid,local,role);
        if(sessions.size()>10000)throw fail(503,"SSO_CAPACITY");
        HttpSession old=request.getSession(false);if(old!=null)old.invalidate();
        HttpSession session=request.getSession(true);session.setMaxInactiveInterval(1800);
        var actor=verified;session.setAttribute(ACTOR,actor);session.setAttribute(CSRF,PasswordHash.token());
        sessions.put(session.getId(),session);return actor;
    }
    public Actor actor(HttpSession session){
        if(session==null)return null;
        try{var actor=session.getAttribute(ACTOR);
            if(!(actor instanceof Actor a)||revoked.contains(a.iamSid()))return null;
            JsonNode current=call("session/introspect",Map.of("subject",a.subject(),"sessionId",a.iamSid(),"audience","ehms"));
            return resolve(current,a.subject(),a.iamSid(),a.localSubject(),a.role());}
        catch(IllegalStateException expired){return null;}
    }
    public int revoke(String sid,String presented){
        if(presented==null||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),presented.getBytes(StandardCharsets.UTF_8)))throw fail(403,"SSO_SERVICE_DENIED");
        if(sid==null||sid.isBlank())throw fail(400,"SSO_SID_REQUIRED");
        revoked.add(sid);int count=0;
        for(var entry:sessions.entrySet())try{var actor=(Actor)entry.getValue().getAttribute(ACTOR);if(actor!=null&&sid.equals(actor.iamSid())){entry.getValue().invalidate();sessions.remove(entry.getKey());count++;}}
        catch(IllegalStateException expired){sessions.remove(entry.getKey());}
        return count;
    }
    public void requireService(String presented){
        if(presented==null||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),presented.getBytes(StandardCharsets.UTF_8)))throw fail(403,"SSO_SERVICE_DENIED");
    }
    public void logout(HttpSession session){if(session!=null){
        var actor=session.getAttribute(ACTOR);
        if(actor instanceof Actor a)call("session/logout",Map.of("subject",a.subject(),"sessionId",a.iamSid(),"audience","ehms"));
        sessions.remove(session.getId());try{session.invalidate();}catch(IllegalStateException alreadyRevoked){}}}
    private JsonNode call(String path,Object body){
        try{
            var response=http.send(HttpRequest.newBuilder(URI.create(base+"/auth/sso/"+path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type","application/json").header("X-SSO-Service-Token",token)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==401||response.statusCode()==403)throw fail(401,"SSO_SESSION_REVOKED");
            if(response.statusCode()!=200)throw fail(503,"SSO_IAM_UNAVAILABLE");
            var root=mapper.readTree(response.body());if(root.path("code").asInt(-1)!=0)throw fail(401,"SSO_SESSION_REJECTED");
            return root.path("data");
        }catch(ResponseStatusException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw fail(503,"SSO_IAM_UNAVAILABLE");}
        catch(Exception e){throw fail(503,"SSO_IAM_UNAVAILABLE");}
    }
    private Actor resolve(JsonNode data,String sub,String sid,String local,String role){
        var binding=bindings.path(sub);
        if(!binding.path("enabled").asBoolean(false)||!local.equals(binding.path("localSubject").asText())
            ||!sub.equals(data.path("subject").asText())||!sid.equals(data.path("sessionId").asText())
            ||!local.equals(data.path("localSubject").asText())||!"ehms".equals(data.path("audience").asText())
            ||!data.path("readOnly").isBoolean()||!Set.of("USER","SUPER_ADMIN").contains(role))throw fail(403,"SSO_MAPPING_REJECTED");
        for(String scope:List.of("company","org","domain","zone"))if(!data.path(scope).asText().isBlank())throw fail(403,"SSO_SCOPE_UNSUPPORTED");
        Set<String> granted=new HashSet<>();data.path("projectIds").forEach(v->granted.add(ProjectContext.validate(v.asText())));
        Set<String> localProjects=new HashSet<>();binding.path("projectIds").forEach(v->localProjects.add(ProjectContext.validate(v.asText())));
        granted.retainAll(localProjects);if(granted.isEmpty())throw fail(403,"SSO_PROJECT_NOT_GRANTED");
        boolean readOnly=data.path("readOnly").asBoolean()||role.equals("USER");
        if(role.equals("SUPER_ADMIN")&&!readOnly&&!data.path("superAdmin").asBoolean(false)){
            boolean allowed=false;for(var p:data.path("permissions"))if(p.asText().equals("ehealth:admin"))allowed=true;
            if(!allowed)throw fail(403,"SSO_ADMIN_NOT_GRANTED");
        }
        return new Actor(sub,local,role,sid,readOnly,Set.copyOf(granted));
    }
    private static ResponseStatusException fail(int code,String message){return new ResponseStatusException(HttpStatus.valueOf(code),message);}
}
