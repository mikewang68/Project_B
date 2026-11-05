package com.bdemo.iam.scope;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bdemo.iam.common.BizException;
import com.bproject.iam.context.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ScopeProjectRoleVisibilityTest {
  UserContext actor(String level,String project) {
    var c=mock(UserContext.class);when(c.scopeMode()).thenReturn(ScopeSql.Mode.SCOPED);when(c.adminLevel()).thenReturn(level);when(c.projectId()).thenReturn(project);return c;
  }
  JdbcTemplate database() {
    var db=mock(JdbcTemplate.class);when(db.queryForList(anyString(),eq("role"))).thenReturn(List.of(Map.of("role_scope","PROJECT","project_id","PROJECT_TEST")));
    when(db.queryForMap(anyString(),eq("role"))).thenReturn(Map.of("role_scope","PROJECT"));return db;
  }
  @Test void l2CanSeeItsProjectReadingRoleWithoutSeeingOtherProjects() {
    var service=new ScopeProvisioning(database(),mock(ScopeContextService.class));
    assertTrue(service.visible("ROLE","role",actor("L2_PROJECT","PROJECT_TEST")));
    assertFalse(service.visible("ROLE","role",actor("L2_PROJECT","PROJECT_OTHER")));
  }
  @Test void l3CannotAdoptAProjectWideRole() {
    assertFalse(new ScopeProvisioning(database(),mock(ScopeContextService.class)).visible("ROLE","role",actor("L3_SUBSYSTEM","PROJECT_TEST")));
  }
  @Test void projectRoleVisibilityDoesNotGiveL2MutationOwnership() {
    var contexts=mock(ScopeContextService.class);var manager=actor("L2_PROJECT","PROJECT_TEST");when(contexts.requireManager(true)).thenReturn(manager);
    assertThrows(BizException.class,()->new ScopeProvisioning(database(),contexts).requireOwned("ROLE","role"));
  }
}
