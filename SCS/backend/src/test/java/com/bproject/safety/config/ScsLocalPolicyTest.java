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
    assertThrows(ScsIamTicketValidator.InvalidTicketException.class, () -> scoped.resolve(claims));
    var mismatched = new HashMap<String,Object>(claims);
    mismatched.put("company","c-99");
    assertThrows(ScsIamTicketValidator.InvalidTicketException.class, () -> scoped.resolve(mismatched));
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
