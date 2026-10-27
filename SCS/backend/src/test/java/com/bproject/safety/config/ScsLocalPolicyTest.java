package com.bproject.safety.config;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScsLocalPolicyTest {
  @TempDir Path directory;

  @Test
  void readsRequireBothLocalRouteGrantAndCurrentCentralPermission() throws Exception {
    var policy = policy();
    var actor = policy.resolve(claims(List.of("safety:fence:list:view"), false, false));
    assertTrue(policy.permits(actor, "GET", "/api/v1/fences"));
    assertFalse(policy.permits(actor, "GET", "/api/v1/rules"));
    assertFalse(policy.permits(actor, "GET", "/api/v1/fences/export"));
    var downgraded = policy.resolve(claims(List.of(), false, false));
    assertFalse(policy.permits(downgraded, "GET", "/api/v1/fences"));
    assertTrue(policy.permits(downgraded, "GET", "/api/v1/auth/me"));
  }

  @Test
  void readonlyVetoWinsEvenWithFullAdminAndExplicitWriteRoute() throws Exception {
    var policy = policy();
    var actor = policy.resolve(claims(List.of("safety:fence:list:view", "safety:fence:edit"), true, true));
    assertFalse(actor.admin());
    assertFalse(policy.permits(actor, "POST", "/api/v1/fences"));
    assertTrue(policy.permits(actor, "POST", "/api/v1/auth/sso/logout"));
  }

  @Test
  void applicationAdminNeedsExplicitLocalAdminAndCurrentCentralGrant() throws Exception {
    var policy=policy();
    var current=new HashMap<String,Object>(claims(List.of(),false,false));
    current.put("applicationAdmin",true);
    assertTrue(policy.resolve(current).admin());
    assertTrue(policy.permits(policy.resolve(current),"POST","/api/v1/ai/models"));
    current.put("applicationAdmin",false);
    assertFalse(policy.resolve(current).admin());
    assertFalse(policy.permits(policy.resolve(current),"POST","/api/v1/ai/models"));
    current.put("applicationAdmin",true);current.put("readOnly",true);
    assertFalse(policy.resolve(current).admin());
  }

  @Test
  void nonBlankDataScopesWithoutObjectFilteringAreRejected() throws Exception {
    var policy = policy();
    var claims = new HashMap<String,Object>(claims(List.of("safety:fence:list:view"), false, false));
    claims.put("company","c-01");claims.put("org","o-02");claims.put("domain","d-03");claims.put("zone","");
    var bindings = new ObjectMapper();var path = directory.resolve("scoped-bindings.json");
    Files.writeString(path, bindings.writeValueAsString(Map.of("subject-a", Map.of(
        "enabled", true, "localSubject", "operator", "role", "ADMIN",
        "company", "c-01", "org", "o-02", "domain", "d-03", "zone", "",
        "permissions", List.of("safety:fence:list:view")))));
    var scoped = new ScsLocalPolicy(bindings, path.toString());
    // 非空范围必须明确报"不支持该数据范围"，而不是笼统的"票据无效"。
    var unsupported = assertThrows(ScsLocalPolicy.UnsupportedDataScopeException.class, () -> scoped.resolve(claims));
    assertTrue(unsupported.getMessage().contains("尚不支持"));
    var mismatched = new HashMap<String,Object>(claims);
    mismatched.put("company","c-99");
    assertThrows(ScsLocalPolicy.UnsupportedDataScopeException.class, () -> scoped.resolve(mismatched));
  }

  // ---------- 空值契约：缺省 / JSON null / 空串 / 纯空白 归一化为空；"null" 与非法类型不算空 ----------

  @Test
  void absentNullEmptyAndBlankScopesAreNormalizedToEmpty() throws Exception {
    var bindings = scopedBindings(Map.of());
    // 缺省字段：票据与绑定都没有 scope 字段
    assertNotNull(bindings.resolve(claims(List.of("safety:fence:list:view"), false, false)));
    // 显式 JSON null 与空串、纯空白互相匹配
    var ticketNulls = new HashMap<String,Object>(claims(List.of("safety:fence:list:view"), false, false));
    ticketNulls.put("company", null);
    ticketNulls.put("org", "");
    ticketNulls.put("domain", "   ");
    assertNotNull(bindings.resolve(ticketNulls));
  }

  @Test
  void literalNullStringIsNotTreatedAsEmpty() throws Exception {
    var ticket = new HashMap<String,Object>(claims(List.of("safety:fence:list:view"), false, false));
    ticket.put("company", "null");
    // 绑定侧为空、票据侧是字符串 "null"：不一致，且非空，必须拒绝而不是当作空值放行
    assertThrows(ScsLocalPolicy.UnsupportedDataScopeException.class, () -> scopedBindings(Map.of()).resolve(ticket));
  }

  @Test
  void nonTextualScopeTypesAreRejected() throws Exception {
    for (Object bad : new Object[]{Map.of("k", "v"), List.of("a"), Boolean.TRUE, 42, 3.5}) {
      var ticket = new HashMap<String,Object>(claims(List.of("safety:fence:list:view"), false, false));
      ticket.put("company", bad);
      var error = assertThrows(ScsLocalPolicy.UnsupportedDataScopeException.class,
          () -> scopedBindings(Map.of()).resolve(ticket), "类型 " + bad.getClass().getSimpleName() + " 应被拒绝");
      assertTrue(error.getMessage().contains("类型非法"), "应说明是类型问题: " + error.getMessage());
    }
  }

  @Test
  void emptyScopeDoesNotGrantGlobalAdmin() throws Exception {
    // 空范围不得自动提升为全局身份：未显式授权时仍按普通权限判定
    var actor = scopedBindings(Map.of()).resolve(claims(List.of("safety:fence:list:view"), false, false));
    assertFalse(actor.admin());
    assertFalse(scopedBindings(Map.of()).permits(actor, "POST", "/api/v1/fences"));
  }

  /** 构造带 scope 字段（值可自定义）的绑定文件；未给出的字段保持缺省。 */
  private ScsLocalPolicy scopedBindings(Map<String, Object> scopeOverrides) throws Exception {
    var json = new ObjectMapper();
    var path = directory.resolve("scope-" + Math.abs(scopeOverrides.hashCode()) + ".json");
    var entry = new HashMap<String, Object>();
    entry.put("enabled", true);
    entry.put("localSubject", "operator");
    entry.put("role", "ADMIN");
    entry.put("permissions", List.of("safety:fence:list:view"));
    entry.putAll(scopeOverrides);
    Files.writeString(path, json.writeValueAsString(Map.of("subject-a", entry)));
    return new ScsLocalPolicy(json, path.toString());
  }

  private ScsLocalPolicy policy() throws Exception {
    var json = new ObjectMapper(); var path = directory.resolve("bindings.json");
    Files.writeString(path, json.writeValueAsString(Map.of("subject-a", Map.of(
        "enabled", true, "localSubject", "operator", "role", "ADMIN",
        "permissions", List.of("safety:fence:list:view", "safety:fence:edit"),
        "routes", List.of(
            Map.of("method", "GET", "pathPattern", "/api/v1/fences", "permission", "safety:fence:list:view"),
            Map.of("method", "POST", "pathPattern", "/api/v1/fences", "permission", "safety:fence:edit"))))));
    return new ScsLocalPolicy(json, path.toString());
  }

  private Map<String,Object> claims(List<String> permissions, boolean full, boolean readonly) {
    return Map.of("sub", "subject-a", "localSubject", "operator", "readOnly", readonly,
        "superAdmin", full, "permissions", permissions);
  }
}
