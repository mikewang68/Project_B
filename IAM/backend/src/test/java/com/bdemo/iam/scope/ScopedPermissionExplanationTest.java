package com.bdemo.iam.scope;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bdemo.iam.insight.EffectivePermissionService;
import com.bdemo.iam.permission.mapper.PermissionMapper;
import com.bdemo.iam.role.mapper.RoleMapper;
import com.bdemo.iam.role.dto.RoleBrief;
import com.bdemo.iam.security.*;
import com.bdemo.iam.user.domain.User;
import com.bdemo.iam.user.mapper.UserMapper;
import com.bproject.iam.context.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class ScopedPermissionExplanationTest {
  @Test void explanationUsesOnlyCurrentScopedActionsAndTheirVerifiedRoleSources() {
    var users=mock(UserMapper.class);var roles=mock(RoleMapper.class);var permissions=mock(PermissionMapper.class);
    var loader=mock(LoginUserLoader.class);var db=mock(JdbcTemplate.class);
    var user=new User();user.setId("u");user.setUsername("operator");user.setName("Operator");
    when(users.selectById("u")).thenReturn(user);
    when(roles.selectActiveRolesByUser("u")).thenReturn(List.of(new RoleBrief("r","BR_r","Viewer")));
    when(permissions.selectAllButtons()).thenReturn(List.of());
    var source=Map.<String,Object>of("role_id","r","stable_menu_key","SYS:logs","op_code","VIEW","permission_code","sys:log:list:view");
    var revoked=Map.<String,Object>of("role_id","r","stable_menu_key","SYS:logs","op_code","EXPORT","permission_code","sys:log:list:export");
    when(db.queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(List.of(source,revoked));
    var actor=new LoginUser("u","operator","Operator",false,Set.of("sys:log:list:view"));
    var list=List.of(new ScopeTriple("P","R","SYS"));var sql=ScopeSql.build(ScopeSql.Mode.SCOPED,list);
    actor.setUserContext(new UserContext(2,"u","operator","u","Operator","L4_OPERATOR",false,"IAM","P","R","SYS",ScopeSql.Mode.SCOPED,list,list,Set.of("BR_r"),Set.of("SYS:logs"),Map.of("SYS:logs",Set.of("VIEW")),Set.of(),"v1",sql.my_info(),sql.my_project_zone_info(),sql.my_info_template(),sql.my_project_zone_info_template(),sql.scope_params()));
    when(loader.load("u")).thenReturn(actor);
    var service=new EffectivePermissionService(users,roles,permissions,loader);ReflectionTestUtils.setField(service,"scopeDb",db);
    var result=service.getView("u");
    assertEquals(1,result.getPermissions().size());assertEquals("sys:log:list:view",result.getPermissions().get(0).getPermissionCode());
    assertEquals("r",result.getPermissions().get(0).getSourceRoles().get(0).getId());
    verify(roles,never()).selectRolePermissionRows(org.mockito.ArgumentMatchers.anyList());
  }
}
