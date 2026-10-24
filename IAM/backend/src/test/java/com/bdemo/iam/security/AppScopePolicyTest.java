package com.bdemo.iam.security;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bdemo.iam.common.BizException;
import com.bdemo.iam.role.mapper.RoleMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class AppScopePolicyTest {
  RoleMapper roles=mock(RoleMapper.class);AppScopePolicy policy=new AppScopePolicy(roles);
  LoginUser actor=new LoginUser("iam-admin","iam-admin","IAM",false,Set.of("iam:user:list:view","iam:role:perm:edit"));
  @BeforeEach void setup(){actor.setScopedApps(Set.of("IAM"));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of()));}
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  @Test void nameAndPermissionPrefixCannotGrantApplications(){when(roles.selectAllowedApps("iam-admin")).thenReturn(List.of("IAM"));assertEquals(Set.of("IAM"),policy.allowedApps("iam-admin"));assertFalse(actor.mayEnter("SYS"));}
  @Test void cannotResetOtherSystemOrUnknownAccount(){when(roles.selectManagementOwner("USER","foreign")).thenReturn("WMS");assertThrows(BizException.class,()->policy.requireOwned("USER","foreign"));assertFalse(policy.visible("USER","unknown"));}
  @Test void ownershipCannotHideACrossSystemRoleGrant(){when(roles.selectManagementOwner("USER","mixed")).thenReturn("IAM");when(roles.selectAllowedApps("mixed")).thenReturn(List.of("IAM","SYS"));assertThrows(BizException.class,()->policy.requireOwned("USER","mixed"));}
  @Test void ownsOnlyItsAccountAndRole(){when(roles.selectManagementOwner("USER","own")).thenReturn("IAM");when(roles.selectAllowedApps("own")).thenReturn(List.of("IAM"));assertDoesNotThrow(()->policy.requireOwned("USER","own"));}
  @Test void cannotDelegateForeignOrUnheldPermissions(){when(roles.selectPermissionSystem("sys:config:edit")).thenReturn("sys");when(roles.selectPermissionSystem("iam:user:delete")).thenReturn("iam");assertThrows(BizException.class,()->policy.requireDelegable(List.of("sys:config:edit")));assertThrows(BizException.class,()->policy.requireDelegable(List.of("iam:user:delete")));}
  @Test void allReservedRoleSpellingsAreProtected(){for(String code:List.of("super_admin","PLATFORM_ADMIN_FULL","platform_auditor_readonly"))assertThrows(BizException.class,()->policy.requireAssignableRole("r",code));}
  @Test void readonlyStillVetoesManagementWithAdministratorFlag(){actor.setSuperAdmin(true);actor.setReadOnly(true);assertThrows(BizException.class,()->policy.requireOwned("USER","own"));assertThrows(BizException.class,()->policy.requireDelegable(List.of()));}
  @Test void platformAdministratorKeepsCrossSystemMaintenance(){actor.setSuperAdmin(true);assertDoesNotThrow(()->policy.requireOwned("USER","foreign"));}
  @Test void editingPermissionsKeepsExistingApplicationAdministrationLevel(){
    when(roles.selectRoleAdminApps("own-role")).thenReturn(List.of("IAM"));
    policy.updateRoleScopes("own-role");
    verify(roles).insertAppScopeLevel("own-role","IAM","ADMIN");
    verify(roles,never()).insertAppScopeLevel("own-role","SYS","ADMIN");
  }
}
