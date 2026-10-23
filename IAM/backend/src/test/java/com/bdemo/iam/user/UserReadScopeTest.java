package com.bdemo.iam.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bdemo.iam.common.BizException;
import com.bdemo.iam.org.OrgService;
import com.bdemo.iam.permission.PermissionService;
import com.bdemo.iam.provisioning.IdentityTasks;
import com.bdemo.iam.role.mapper.RoleMapper;
import com.bdemo.iam.security.LoginUser;
import com.bdemo.iam.user.domain.User;
import com.bdemo.iam.user.mapper.UserMapper;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class UserReadScopeTest {
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }
  @Test
  void scopedUserCanRestoreOwnProfileWithoutAcquiringUnscopedUserReads() {
    var mapper=mock(UserMapper.class);var service=service(mapper);var actor=actor(Set.of("auditor"));
    var self=new User();self.setId("self");when(mapper.selectById("self")).thenReturn(self);
    authenticate(actor);
    assertEquals("self",service.currentProfile(actor).getId());
    assertThrows(BizException.class,()->service.get("self"));
    assertTrue(service.list(null,null,null).isEmpty());
  }
  @Test
  void explicitGlobalReadonlyRoleCanReadUsersAcrossCompanyScopes() {
    var mapper=mock(UserMapper.class);var service=service(mapper);var actor=actor(Set.of("platform_auditor_readonly"));
    var other=new User();other.setId("other");other.setCompanyCode("company-b");when(mapper.selectById("other")).thenReturn(other);
    authenticate(actor);
    assertEquals("other",service.get("other").getId());
    service.list(null,null,null);
    verify(mapper).selectList(UserMapper.COLUMNS,null,null,null,null,null);
    assertFalse(actor.isSuperAdmin());assertTrue(actor.isReadOnly());
  }
  private UserService service(UserMapper mapper) {
    var service=new UserService(mapper,mock(RoleMapper.class),mock(OrgService.class),mock(PermissionService.class),mock(PasswordEncoder.class));
    var identities=mock(IdentityTasks.class);when(identities.status(anyString())).thenReturn(Map.of("state","NOT_PROVISIONED"));
    ReflectionTestUtils.setField(service,"identities",identities);return service;
  }
  private LoginUser actor(Set<String> roles) {
    var user=new LoginUser("self","operator","Operator",false,Set.of("iam:user:list:view"));
    user.setReadOnly(true);user.setRoles(roles);return user;
  }
  private void authenticate(LoginUser user) {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
  }
}
