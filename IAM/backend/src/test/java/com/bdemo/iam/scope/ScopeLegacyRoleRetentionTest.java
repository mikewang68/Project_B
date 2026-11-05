package com.bdemo.iam.scope;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.bdemo.iam.common.BizException;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ScopeLegacyRoleRetentionTest {
  final JdbcTemplate db=mock(JdbcTemplate.class);
  final ScopeContextService contexts=mock(ScopeContextService.class);
  final ScopeProvisioning service=new ScopeProvisioning(db,contexts);
  final Map<String,Object> account=new HashMap<>(Map.of("scope_state","V2_READY","admin_level","L4_OPERATOR",
      "project_id","PROJECT_TEST","region_id","REGION_TEST","subsystem_code","WMS","read_only",false));
  @BeforeEach void setup() {
    when(contexts.enabled()).thenReturn(true);
    when(contexts.account("user-a")).thenReturn(account);
    when(db.queryForList(contains("JOIN iam.iam_role r"),eq(String.class),eq("user-a")))
        .thenReturn(List.of("retained-legacy"));
    when(db.queryForList(startsWith("SELECT role_scope"),eq("business")))
        .thenReturn(List.of(role("REGION_SYSTEM","REGION_TEST")));
    when(db.queryForList(startsWith("SELECT role_scope"),eq("new-legacy")))
        .thenReturn(List.of(role("LEGACY","REGION_TEST")));
    when(db.queryForList(startsWith("SELECT role_scope"),eq("retained-legacy")))
        .thenReturn(List.of(role("LEGACY","REGION_TEST")));
    when(db.queryForList(startsWith("SELECT role_scope"),eq("foreign-business")))
        .thenReturn(List.of(role("REGION_SYSTEM","OTHER_REGION")));
  }
  Map<String,Object> role(String scope,String region) {
    return Map.of("role_scope",scope,"project_id","PROJECT_TEST","region_id",region,"subsystem_code","WMS","status","active");
  }
  @Test void retainsOnlyAlreadyBoundLegacyAlongsideValidBusinessRole() {
    assertDoesNotThrow(()->service.validateExistingUserRoles("user-a",List.of("retained-legacy","business")));
    verify(db,never()).queryForList(startsWith("SELECT role_scope"),eq("retained-legacy"));
  }
  @Test void arbitraryLegacyCannotBeAddedToExistingUser() {
    assertThrows(BizException.class,()->service.validateExistingUserRoles("user-a",List.of("retained-legacy","business","new-legacy")));
  }
  @Test void crossRegionBusinessRoleRemainsRejected() {
    assertThrows(BizException.class,()->service.validateExistingUserRoles("user-a",List.of("retained-legacy","foreign-business")));
  }
  @Test void legacyAssociationDoesNotReplaceRequiredL4BusinessRole() {
    assertThrows(BizException.class,()->service.validateExistingUserRoles("user-a",List.of("retained-legacy")));
  }
  @Test void revokedParentActionStillRejectsBusinessRole() {
    when(db.queryForList(startsWith("SELECT stable_menu_key"),eq("business")))
        .thenReturn(List.of(Map.of("stable_menu_key","WMS:inventory","op_code","VIEW")));
    when(db.queryForObject(startsWith("SELECT count(*) FROM iam.iam_project_menu_grant"),eq(Integer.class),
        eq("PROJECT_TEST"),eq("REGION_TEST"),eq("WMS"),eq("WMS:inventory"),eq("VIEW"))).thenReturn(0);
    assertThrows(BizException.class,()->service.validateExistingUserRoles("user-a",List.of("retained-legacy","business")));
  }
  @Test void creationNeverUsesExistingUserExemption() {
    var plan=new ScopeProvisioning.Plan("manager","L4_OPERATOR",false,"PROJECT_TEST","REGION_TEST","WMS","parent",List.of());
    assertThrows(BizException.class,()->service.validateRoles(plan,List.of("retained-legacy","business")));
  }
  @Test void inactiveLegacyIsNotRetained() {
    when(db.queryForList(contains("JOIN iam.iam_role r"),eq(String.class),eq("user-a"))).thenReturn(List.of());
    when(db.queryForList(startsWith("SELECT role_scope"),eq("retained-legacy"))).thenReturn(List.of());
    assertThrows(BizException.class,()->service.validateExistingUserRoles("user-a",List.of("retained-legacy","business")));
  }
}
