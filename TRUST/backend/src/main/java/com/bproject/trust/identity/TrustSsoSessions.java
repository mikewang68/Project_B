package com.bproject.trust.identity;

import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Native TRUST sessions bound to current IAM authorization and explicit local users. */
@Service
public class TrustSsoSessions {
  public static final String ATTRIBUTE = "TRUST_SSO_BINDING";
  public record Binding(String subject, String sid, String localSubject, String org) implements java.io.Serializable {}
  public record Current(PlatformPrincipal principal, Set<String> permissions,com.bproject.iam.context.UserContext context) {
    public Current(PlatformPrincipal principal,Set<String> permissions){this(principal,permissions,null);}
  }
  private final String base, credential;
  private final boolean enabled;
  private final JdbcTemplate db;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final Map<String, HttpSession> sessions = new ConcurrentHashMap<>();
  private static final Set<String> READ = Set.of("trust:event:read", "trust:evidence:read", "trust:task:read",
      "trust:audit:read", "trust:operations:read", "trust:identity:read", "trust:wallet:read");
  private static final Set<String> WRITE = Set.of("trust:event:submit", "trust:event:correct", "trust:task:retry",
      "trust:evidence:upload", "trust:evidence:verify", "trust:evidence:export",
      "trust:wallet:manage", "trust:wallet:review");

  public TrustSsoSessions(JdbcTemplate db, @Value("${trust.sso.iam-base-url:}") String base,
      @Value("${trust.sso.service-token:}") String credential, @Value("${trust.sso.enabled:false}") boolean enabled) {
    this.db=db; this.base=base.replaceAll("/$", ""); this.credential=credential; this.enabled=enabled;
  }
  public void authorizeService(String supplied) {
    if (!enabled || base.isBlank() || credential.length()<32) throw new ApiError(503,"TRUST统一登录尚未配置");
    if (supplied==null || !MessageDigest.isEqual(credential.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
      throw new ApiError(403,"服务身份未获授权");
  }
  private JsonNode request(String path, Object body) {
    if (!enabled || base.isBlank() || credential.length()<32) throw new ApiError(503,"统一登录尚未配置");
    try {
      var req=HttpRequest.newBuilder(URI.create(base+"/auth/sso/"+path)).timeout(Duration.ofSeconds(5))
          .header("Content-Type","application/json").header("X-SSO-Service-Token",credential)
          .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
      var response=http.send(req,HttpResponse.BodyHandlers.ofString());
      if(response.statusCode()==401||response.statusCode()==403)throw new ApiError(response.statusCode(),"统一身份或授权已失效");
      if(response.statusCode()!=200)throw new ApiError(503,"统一身份服务暂不可用");
      var root=Json.MAPPER.readTree(response.body());
      if(!root.path("code").isIntegralNumber()||root.path("code").asInt()!=0||!root.path("data").isObject())
        throw new ApiError(503,"统一身份响应不符合契约");
      return root.path("data");
    }catch(ApiError e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiError(503,"统一身份请求中断");}
    catch(Exception e){throw new ApiError(503,"统一身份服务暂不可用");}
  }
  public Binding exchange(String ticket) {
    if(ticket==null||ticket.length()<20||ticket.length()>8192)throw new ApiError(400,"缺少有效票据");
    var a=request("verify-and-consume",Map.of("audience","trust","ticket",ticket));
    var binding=new Binding(a.path("subject").asText(),a.path("sessionId").asText(),a.path("localSubject").asText(),a.path("org").asText());
    resolve(a,binding); return binding;
  }
  public Current current(Binding b) {
    return resolve(request("session/introspect",Map.of("subject",b.subject(),"sessionId",b.sid(),"audience","trust")),b);
  }
  private Current resolve(JsonNode a, Binding b) {
    for (String field : List.of("audience", "subject", "sessionId", "localSubject", "org"))
      if (!a.path(field).isTextual() || a.path(field).asText().isBlank())
        throw new ApiError(403,"身份断言字段无效");
    if(!"trust".equals(a.path("audience").asText())||!b.subject().equals(a.path("subject").asText())
        ||!b.sid().equals(a.path("sessionId").asText())
        ||b.subject().isBlank()||b.sid().isBlank()||b.org().isBlank()||b.localSubject().isBlank()
        ||!b.localSubject().equals(a.path("localSubject").asText())||!b.org().equals(a.path("org").asText())
        ||!a.path("readOnly").isBoolean()||!a.path("permissions").isArray())throw new ApiError(403,"身份范围未获映射");
    List<Map<String,Object>> locals;
    try {
      locals=db.queryForList("SELECT role,org_id FROM trust_users WHERE username=? AND org_id=?",b.localSubject(),b.org());
    } catch (org.springframework.dao.DataAccessException unavailable) {
      throw new ApiError(503,"本地主体映射暂不可用");
    }
    if(locals.size()!=1)throw new ApiError(403,"缺少唯一的业务用户映射");
    String role=String.valueOf(locals.get(0).get("role")).toUpperCase(Locale.ROOT);
    if(!Set.of("ADMIN","EDITOR","VIEWER").contains(role))throw new ApiError(403,"业务角色不可用");
    boolean readOnly=a.path("readOnly").asBoolean()||"VIEWER".equals(role);
    var context=TrustCentralScope.validate(a,b.subject());
    Set<String> local=new HashSet<>(READ);
    if(!readOnly&&Set.of("ADMIN","EDITOR").contains(role))local.addAll(WRITE);
    Set<String> central=new HashSet<>();
    for (var value : a.path("permissions")) {
      if (!value.isTextual() || value.asText().isBlank()) throw new ApiError(403,"权限断言无效");
      central.add(value.asText());
    }
    if(context!=null)local=new HashSet<>(TrustCentralScope.permissions(context,readOnly));
    else local.retainAll(central); // Legacy superAdmin never bypasses explicit local/central grants.
    return new Current(new PlatformPrincipal(b.subject(),a.path("username").asText(),b.org(),readOnly),Set.copyOf(local),context);
  }
  public synchronized void bind(HttpSession session, Binding b) {
    if(sessions.size()>=10000){sessions.entrySet().removeIf(e->{try{e.getValue().getCreationTime();return false;}catch(IllegalStateException gone){return true;}});}
    if(sessions.size()>=10000)throw new ApiError(503,"业务会话容量暂不可用");
    session.setAttribute(ATTRIBUTE,b);sessions.put(session.getId(),session);
  }
  public synchronized int revoke(String sid, String subject) {
    if(sid==null||sid.isBlank()||subject==null||subject.isBlank())throw new ApiError(400,"缺少会话标识");
    int count=0;
    for(var entry:sessions.entrySet())try{
      if(entry.getValue().getAttribute(ATTRIBUTE) instanceof Binding b&&sid.equals(b.sid())&&subject.equals(b.subject())){
        entry.getValue().invalidate();sessions.remove(entry.getKey());count++;
      }
    }catch(IllegalStateException gone){sessions.remove(entry.getKey());}
    return count;
  }
  public synchronized void forget(HttpSession session) {
    sessions.remove(session.getId());
  }
  public void logout(Binding binding){request("session/logout",Map.of("subject",binding.subject(),"sessionId",binding.sid(),"audience","trust"));}
}
