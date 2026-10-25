package com.bproject.safety.config;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Explicit business-role binding. No role is accepted from browser headers. */
@Service
public class ScsLocalPolicy {
  private final ObjectMapper mapper;private final String file;
  public record Actor(String subject,String username,Set<String> roles,Set<String> permissions,boolean readOnly,boolean admin){}
  public ScsLocalPolicy(ObjectMapper mapper,@Value("${app.security.sso-bindings-file:}")String file){this.mapper=mapper;this.file=file;}
  public Actor resolve(Map<String,Object> claims){
    try{
      var c=mapper.valueToTree(claims);String subject=c.path("sub").asText();
      var bindings=mapper.readTree(Files.readString(Path.of(file)));var b=bindings.path(subject);
      String local=c.path("localSubject").asText(),role=b.path("role").asText();
      if(!b.path("enabled").asBoolean(false)||local.isBlank()||!local.equals(b.path("localSubject").asText())
          ||!Set.of("ADMIN","OPERATOR","VIEWER").contains(role)||!c.path("readOnly").isBoolean())
        throw new IllegalArgumentException("No business mapping");
      for(String scope:List.of("company","org","domain","zone"))
        // 票据 claims 与绑定文件两边一致即放行；非空 scope 是合法输入（IAM 契约：空=无范围），不得按非空拒绝
        if(!c.path(scope).asText().equals(b.path(scope).asText()))
          throw new IllegalArgumentException("Unsupported data scope");
      boolean readOnly=c.path("readOnly").asBoolean()||role.equals("VIEWER");
      boolean admin=role.equals("ADMIN")&&(c.path("superAdmin").asBoolean(false)||c.path("applicationAdmin").asBoolean(false))&&!readOnly;
      Set<String> permissions=new HashSet<>();b.path("permissions").forEach(v->permissions.add(v.asText()));
      if(!c.path("superAdmin").asBoolean(false)){
        Set<String> central=new HashSet<>();c.path("permissions").forEach(v->central.add(v.asText()));permissions.retainAll(central);
      }
      Set<String> roles=readOnly?Set.of(SecurityInterceptor.ROLE_PLATFORM_AUDITOR_READONLY):Set.of(role);
      return new Actor(subject,c.path("username").asText(local),roles,Set.copyOf(permissions),readOnly,admin);
    }catch(Exception e){throw new ScsIamTicketValidator.InvalidTicketException("本地业务映射或范围不可用");}
  }
  public boolean permits(Actor actor,String method,String path){
    // Logout is session maintenance, available to read-only users too; CSRF is checked by the interceptor.
    if (method.equals("POST") && path.equals("/api/v1/auth/sso/logout")) return true;
    boolean safe=Set.of("GET","HEAD","OPTIONS").contains(method);
    boolean export=path.matches(".*/(export|download)(/.*)?");
    if(actor.readOnly()&&!safe)return false;
    if(safe&&path.equals("/api/v1/auth/me"))return true;
    if(safe&&path.equals("/ws/live"))return actor.admin() || actor.permissions().stream().anyMatch(p->p.endsWith(":view") || p.endsWith(":read"));
    if(actor.admin())return true;
    String required=ScsRoutePermissions.required(method,path);
    if(required!=null)return actor.permissions().contains(required);
    try{
      var root=mapper.readTree(Files.readString(Path.of(file)));var rules=root.path(actor.subject()).path("routes");
      for(var rule:rules)if(method.equals(rule.path("method").asText())&&path.matches(rule.path("pathPattern").asText()))
        return actor.permissions().contains(rule.path("permission").asText());
    }catch(Exception ignored){}return false;
  }
}
