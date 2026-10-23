package com.bdemo.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bdemo.system.SystemUserMapper;
import java.nio.file.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmcsSsoPermissionTest {
  @TempDir Path directory;
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
  }
}
