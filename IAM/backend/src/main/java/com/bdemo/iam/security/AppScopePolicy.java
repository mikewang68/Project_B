package com.bdemo.iam.security;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.navigation.enums.AppCodeEnum;
import com.bdemo.iam.role.mapper.RoleMapper;
import java.util.*;
import org.springframework.stereotype.Service;

/** Database-owned application boundaries, independent of permission-code spelling. */
@Service
public class AppScopePolicy {
  private final RoleMapper roles;
  public AppScopePolicy(RoleMapper roles) { this.roles=roles; }
  public static Set<String> allApps() {
    Set<String> values=new TreeSet<>();for(var app:AppCodeEnum.values())values.add(app.name());return values;
  }
  public Set<String> allowedApps(String userId) { return new TreeSet<>(roles.selectAllowedApps(userId)); }
  public static boolean globalRead(LoginUser actor) {
    return actor!=null && (actor.isSuperAdmin() || actor.isReadOnly() && actor.getRoles().stream()
        .anyMatch(r->"PLATFORM_AUDITOR_READONLY".equalsIgnoreCase(r)));
  }
  public boolean visible(String kind,String id) {
    var actor=JwtAuthFilter.currentUser();
    if(actor==null || globalRead(actor))return true;
    return "IAM".equals(roles.selectManagementOwner(kind,id)) && actor.getScopedApps().contains("IAM");
  }
  public void requireOwned(String kind,String id) {
    var actor=JwtAuthFilter.currentUser();
    if(actor==null || actor.isReadOnly())throw BizException.forbidden("管理操作未获授权");
    if(!actor.isSuperAdmin() && (!visible(kind,id) || kind.equals("USER") &&
        roles.selectAllowedApps(id).stream().anyMatch(a->!"IAM".equals(a)) || kind.equals("ROLE") &&
        roles.selectRoleApps(id).stream().anyMatch(a->!"IAM".equals(a))))
      throw BizException.forbidden("不能管理其他系统或平台对象");
  }
  public void recordCreated(String kind,String id) {
    var actor=JwtAuthFilter.currentUser();
    String owner=actor!=null && !actor.isSuperAdmin()?"IAM":"GLOBAL";
    roles.insertManagementOwner(kind,id,owner);
    if(kind.equals("ROLE")) updateRoleScopes(id);
  }
  public void updateRoleScopes(String id) {
    var actor=JwtAuthFilter.currentUser();Set<String> apps=new TreeSet<>();
    if(actor!=null && !actor.isSuperAdmin()) apps.add("IAM");
    else for(String code:roles.selectPermissionCodes(id))for(var app:AppCodeEnum.values())
      if(app.getIamSystemCode().equals(roles.selectPermissionSystem(code)))apps.add(app.name());
    Set<String> priorAdmins=new HashSet<>(roles.selectRoleAdminApps(id));
    roles.deleteAppScopes(id);for(String app:apps)roles.insertAppScopeLevel(id,app,priorAdmins.contains(app)?"ADMIN":"BUSINESS");
  }
  public void requireDelegable(List<String> codes) {
    var actor=JwtAuthFilter.currentUser();
    if(actor==null || actor.isReadOnly())throw BizException.forbidden("授权操作未获授权");
    if(actor.isSuperAdmin())return;
    if(!actor.getScopedApps().equals(Set.of("IAM")))throw BizException.forbidden("账号不能分配权限");
    for(String code:codes==null?List.<String>of():codes) {
      if(!"iam".equals(roles.selectPermissionSystem(code)) || !actor.hasPermission(code))
        throw BizException.forbidden("不能授予其他系统或本人未拥有的权限");
    }
  }
  public void requireAssignableRole(String roleId,String code) {
    var actor=JwtAuthFilter.currentUser();
    if(actor==null || actor.isReadOnly())throw BizException.forbidden("角色分配未获授权");
    boolean reserved=Set.of("SUPER_ADMIN","PLATFORM_ADMIN_FULL","PLATFORM_AUDITOR_READONLY").contains(code.toUpperCase(Locale.ROOT));
    if(!actor.isSuperAdmin() && (reserved || !visible("ROLE",roleId)
        || !roles.selectRoleApps(roleId).equals(List.of("IAM"))))throw BizException.forbidden("不能分配跨系统或平台角色");
  }
}
