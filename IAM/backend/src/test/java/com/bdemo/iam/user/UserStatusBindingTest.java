package com.bdemo.iam.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bdemo.iam.common.BizException;
import com.bdemo.iam.org.OrgService;
import com.bdemo.iam.permission.PermissionService;
import com.bdemo.iam.provisioning.IdentityTasks;
import com.bdemo.iam.role.mapper.RoleMapper;
import com.bdemo.iam.scope.ScopeProvisioning;
import com.bdemo.iam.security.LoginUser;
import com.bdemo.iam.security.mapper.SessionMapper;
import com.bdemo.iam.user.domain.User;
import com.bdemo.iam.user.mapper.UserMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class UserStatusBindingTest {
  static class Fixture {
    final UserMapper users=mock(UserMapper.class);
    final SessionMapper sessions=mock(SessionMapper.class);
    final ScopeProvisioning bindings=mock(ScopeProvisioning.class);
    final IdentityTasks identities=mock(IdentityTasks.class);
    final User user=new User();
    final UserService service=spy(new UserService(users,mock(RoleMapper.class),mock(OrgService.class),mock(PermissionService.class),mock(PasswordEncoder.class)));
    Fixture(String status) {
      user.setId("subject-a");user.setStatus(status);
      when(users.selectById(user.getId())).thenReturn(user);
      when(users.selectRoleIds(user.getId())).thenReturn(List.of());
      doReturn(user).when(service).get(user.getId());
      ReflectionTestUtils.setField(service,"sessions",sessions);
      ReflectionTestUtils.setField(service,"scopeProvisioning",bindings);
      ReflectionTestUtils.setField(service,"identities",identities);
    }
  }
  @Test void enablingRecomputesBindingsAfterPersistingStatusAndRevokingOldSessions() {
    var f=new Fixture("disabled");f.service.setStatus(f.user.getId(),"active",null);
    var order=inOrder(f.sessions,f.users,f.bindings,f.identities);
    order.verify(f.sessions).revokeUser(f.user.getId());
    order.verify(f.users).updateStatus(eq(f.user.getId()),eq("active"),any());
    order.verify(f.bindings).refreshReadonlyBindings(f.user.getId());
    order.verify(f.identities).sync(f.user.getId(),"USER_STATUS");
  }
  @Test void disablingAlsoRecomputesBindingsInsteadOfLeavingEnabledTargets() {
    var f=new Fixture("active");f.service.setStatus(f.user.getId(),"disabled",null);
    var order=inOrder(f.users,f.bindings);
    order.verify(f.users).updateStatus(eq(f.user.getId()),eq("disabled"),any());
    order.verify(f.bindings).refreshReadonlyBindings(f.user.getId());
  }
  @Test void idempotentEnableRepairsStaleBindingsWithoutRevokingSessionsOrProvisioningIdentityAgain() {
    var f=new Fixture("active");f.service.setStatus(f.user.getId(),"active",null);
    verify(f.bindings).refreshReadonlyBindings(f.user.getId());
    verifyNoInteractions(f.sessions,f.identities);
    verify(f.users,never()).updateStatus(anyString(),anyString(),any());
  }
  @Test void selfDisableIsRejectedBeforeStatusOrBindingChanges() {
    var f=new Fixture("active");var actor=new LoginUser(f.user.getId(),"operator","Operator",false,Set.of());
    assertThrows(BizException.class,()->f.service.setStatus(f.user.getId(),"disabled",actor));
    verifyNoInteractions(f.sessions,f.bindings,f.identities);
    verify(f.users,never()).updateStatus(anyString(),anyString(),any());
  }
}
