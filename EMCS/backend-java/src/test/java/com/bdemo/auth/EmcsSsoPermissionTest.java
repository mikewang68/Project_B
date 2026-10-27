package com.bdemo.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bdemo.system.SystemUserMapper;
import com.bdemo.system.UserAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmcsSsoPermissionTest {
  @TempDir Path directory;
  Path bindingFile() throws Exception {
    var path=directory.resolve("active.json");
    Files.writeString(path,"{\"subject-a\":{\"enabled\":true,\"userId\":42}}");return path;
  }
  @Test
  void dedicatedApplicationAdminRequiresCurrentGrantAndReadonlyStillWins() throws Exception {
    var users=mock(SystemUserMapper.class);
    when(users.findById(42)).thenReturn(Optional.of(new UserAccount(42,7L,"operator","Operator","","", "0","0")));
    when(users.findRoleKeys(42)).thenReturn(List.of("lw_emcs_admin"));
    when(users.findPermissions(42)).thenReturn(List.of());
    var service=new EmcsSsoSessions(users,"http://127.0.0.1:1","test-service-identity-at-least-32-characters",bindingFile().toString(),true);
    var assertion=new ObjectMapper().readTree("""
        {"subject":"subject-a","sessionId":"sid-a","localSubject":"operator","audience":"emcs",
         "allowedAppCodes":["EMCS"],"readOnly":false,"superAdmin":false,"applicationAdmin":true,"permissions":[]}
        """);
    var resolve=EmcsSsoSessions.class.getDeclaredMethod("resolve",EmcsSsoSessions.Binding.class,com.fasterxml.jackson.databind.JsonNode.class);resolve.setAccessible(true);
    var b=new EmcsSsoSessions.Binding("subject-a","sid-a",42,"operator");
    var current=(EmcsSsoSessions.Current)resolve.invoke(service,b,assertion);
    assertTrue(current.roles().contains("admin"));
    assertTrue(service.permits(b,current,"POST","/system/dict/type"));
    ((com.fasterxml.jackson.databind.node.ObjectNode)assertion).put("applicationAdmin",false);
    current=(EmcsSsoSessions.Current)resolve.invoke(service,b,assertion);
    assertFalse(current.roles().contains("admin"));
    assertFalse(service.permits(b,current,"POST","/system/dict/type"));
    ((com.fasterxml.jackson.databind.node.ObjectNode)assertion).put("applicationAdmin",true).put("readOnly",true);
    current=(EmcsSsoSessions.Current)resolve.invoke(service,b,assertion);
    assertFalse(current.roles().contains("admin"));
    assertFalse(service.permits(b,current,"POST","/system/dict/type"));
  }
  @Test
  void readonlyAdminCannotExposeAdminControlsButKeepsDispatchDataScope() throws Exception {
    var users=mock(SystemUserMapper.class);
    when(users.findById(42)).thenReturn(Optional.of(new UserAccount(42,7L,"operator","Operator","","", "0","0")));
    when(users.findRoleKeys(42)).thenReturn(List.of("admin","dispatch"));
    when(users.findPermissions(42)).thenReturn(List.of("energy:alert:list","energy:alert:edit"));
    var service=new EmcsSsoSessions(users,"http://127.0.0.1:1","test-service-identity-at-least-32-characters",bindingFile().toString(),true);
    var assertion=new ObjectMapper().readTree("""
        {"subject":"subject-a","sessionId":"sid-a","localSubject":"operator","audience":"emcs",
         "allowedAppCodes":["EMCS"],"readOnly":true,"superAdmin":true,"permissions":["ems:energy:alert:list","ems:energy:alert:edit"]}
        """);
    var resolve=EmcsSsoSessions.class.getDeclaredMethod("resolve",EmcsSsoSessions.Binding.class,com.fasterxml.jackson.databind.JsonNode.class);
    resolve.setAccessible(true);
    var current=(EmcsSsoSessions.Current)resolve.invoke(service,new EmcsSsoSessions.Binding("subject-a","sid-a",42,"operator"),assertion);
    assertTrue(current.readOnly());
    assertEquals(Set.of("dispatch","PLATFORM_AUDITOR_READONLY"),current.roles());
    assertEquals(Set.of("energy:alert:list"),current.permissions());
  }
  @Test
  void nativeAdminRoleDoesNotBypassCurrentCentralPermissionIntersection() throws Exception {
    var path=directory.resolve("bindings.json");
    Files.writeString(path,"{\"subject-a\":{\"routes\":[{\"method\":\"GET\",\"pathPattern\":\"/system/dict/type/list\",\"permission\":\"system:dict:list\"}]}}");
    var service=new EmcsSsoSessions(mock(SystemUserMapper.class),"http://127.0.0.1:1","test-service-identity-at-least-32-characters",path.toString(),true);
    var binding=new EmcsSsoSessions.Binding("subject-a","sid-a",42,"operator");
    var denied=new EmcsSsoSessions.Current(new AuthenticatedUser(42,"operator"),Set.of("admin"),Set.of(),false);
    assertFalse(service.permits(binding,denied,"POST","/system/dict/type"));
    assertFalse(service.permits(binding,denied,"GET","/system/dict/type/list"));
    var granted=new EmcsSsoSessions.Current(denied.principal(),denied.roles(),Set.of("system:dict:list"),false);
    assertTrue(service.permits(binding,granted,"GET","/system/dict/type/list"));
    assertFalse(service.permits(binding,granted,"DELETE","/system/dict/type/42"));
    assertTrue(service.permits(binding,denied,"POST","/logout"));
    assertTrue(service.permits(binding,denied,"GET","/transport/crypto/frontend-config"));
    assertTrue(service.permits(binding,denied,"GET","/transport/crypto/public-key"));
    assertFalse(service.permits(binding,denied,"POST","/transport/crypto/frontend-config"));
    assertFalse(service.permits(binding,denied,"GET","/transport/crypto/private-key"));
  }
}
