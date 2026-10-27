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

  /**
   * 数据范围暂不受支持。与 {@link ScsIamTicketValidator.InvalidTicketException}（票据本身不可信）
   * 区分开：IAM 下发非空范围在契约上是合法的，只是 SCS 还没有对应的对象过滤能力。
   * 不能把这种情形说成票据格式错误，也不能静默忽略范围当作全局身份。
   */
  public static class UnsupportedDataScopeException extends RuntimeException {
    public UnsupportedDataScopeException(String scope) {
      super("目标系统尚不支持该数据范围: " + scope);
    }
  }

  private static final List<String> SCOPE_FIELDS = List.of("company", "org", "domain", "zone");

  public ScsLocalPolicy(ObjectMapper mapper,@Value("${app.security.sso-bindings-file:}")String file){this.mapper=mapper;this.file=file;}
  public Actor resolve(Map<String,Object> claims){
    try{
      var c=mapper.valueToTree(claims);String subject=c.path("sub").asText();
      var bindings=mapper.readTree(Files.readString(Path.of(file)));var b=bindings.path(subject);
      String local=c.path("localSubject").asText(),role=b.path("role").asText();
      if(!b.path("enabled").asBoolean(false)||local.isBlank()||!local.equals(b.path("localSubject").asText())
          ||!Set.of("ADMIN","OPERATOR","VIEWER").contains(role)||!c.path("readOnly").isBoolean())
        throw new IllegalArgumentException("No business mapping");
      enforceDataScope(c,b);
      boolean readOnly=c.path("readOnly").asBoolean()||role.equals("VIEWER");
      boolean admin=role.equals("ADMIN")&&(c.path("superAdmin").asBoolean(false)||c.path("applicationAdmin").asBoolean(false))&&!readOnly;
      Set<String> permissions=new HashSet<>();b.path("permissions").forEach(v->permissions.add(v.asText()));
      if(!c.path("superAdmin").asBoolean(false)){
        Set<String> central=new HashSet<>();c.path("permissions").forEach(v->central.add(v.asText()));permissions.retainAll(central);
      }
      Set<String> roles=readOnly?Set.of(SecurityInterceptor.ROLE_PLATFORM_AUDITOR_READONLY):Set.of(role);
      return new Actor(subject,c.path("username").asText(local),roles,Set.copyOf(permissions),readOnly,admin);
    }catch(UnsupportedDataScopeException unsupported){
      // 必须原样上抛：被下面的 catch(Exception) 吞掉会变成"票据无效"，掩盖真实原因。
      throw unsupported;
    }catch(Exception e){throw new ScsIamTicketValidator.InvalidTicketException("本地业务映射或范围不可用");}
  }

  /**
   * 数据范围校验（严格边界，不做假隔离）：
   * <ol>
   *   <li>缺省字段、JSON null、空字符串、纯空白统一归一化为空值；</li>
   *   <li>字符串 {@code "null"} 以及其他实际非空值<b>不</b>视为空值；</li>
   *   <li>对象、数组、布尔、数值等非法类型直接拒绝；</li>
   *   <li>归一化后仍比对 IAM 与本地显式映射；</li>
   *   <li>非空范围一律拒绝——SCS 尚无行级过滤，字符串相同不代表真正隔离。</li>
   * </ol>
   */
  private static void enforceDataScope(JsonNode claims, JsonNode binding) {
    for (String scope : SCOPE_FIELDS) {
      String fromTicket = normalizeScope(claims.get(scope), "票据", scope);
      String fromBinding = normalizeScope(binding.get(scope), "本地绑定", scope);
      if (!fromTicket.equals(fromBinding)) {
        throw new UnsupportedDataScopeException(scope + "（票据与本地映射不一致）");
      }
      if (!fromTicket.isEmpty()) {
        throw new UnsupportedDataScopeException(scope + "=" + fromTicket);
      }
    }
  }

  /**
   * 把 scope 字段归一化为空串或非空字符串。
   *
   * @throws UnsupportedDataScopeException 类型非法（对象/数组/布尔/数值）
   */
  private static String normalizeScope(JsonNode node, String source, String scope) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return "";
    }
    if (!node.isTextual()) {
      throw new UnsupportedDataScopeException(scope + "（" + source + "中的类型非法: " + node.getNodeType() + "）");
    }
    // 注意：不得用 asText() 判空——JSON null 会得到字符串 "null"，会被误当非空值。
    return node.textValue().trim();
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
