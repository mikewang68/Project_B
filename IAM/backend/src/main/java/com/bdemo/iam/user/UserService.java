package com.bdemo.iam.user;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.org.OrgService;
import com.bdemo.iam.role.domain.Role;
import com.bdemo.iam.role.mapper.RoleMapper;
import com.bdemo.iam.security.LoginUser;
import com.bdemo.iam.security.LoginUserLoader;
import com.bdemo.iam.user.domain.User;
import com.bdemo.iam.user.mapper.UserMapper;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService implements LoginUserLoader {

  private final UserMapper userMapper;
  private final RoleMapper roleMapper;
  private final OrgService orgService;
  private final com.bdemo.iam.permission.PermissionService permissionService;
  private final PasswordEncoder passwordEncoder;

  public UserService(
      UserMapper userMapper,
      RoleMapper roleMapper,
      OrgService orgService,
      com.bdemo.iam.permission.PermissionService permissionService,
      PasswordEncoder passwordEncoder) {
    this.userMapper = userMapper;
    this.roleMapper = roleMapper;
    this.orgService = orgService;
    this.permissionService = permissionService;
    this.passwordEncoder = passwordEncoder;
  }

  @org.springframework.beans.factory.annotation.Autowired
  private com.bdemo.iam.security.mapper.SessionMapper sessions;

  @org.springframework.beans.factory.annotation.Autowired
  private com.bdemo.iam.provisioning.IdentityTasks identities;

  // ---- 查询 ----

  public List<User> list(String keyword, String status, String roleId) {
    String kw = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
    List<User> users =
        userMapper.selectList(UserMapper.COLUMNS, status, kw == null ? null : keyword.trim(), kw);
    for (User u : users) {
      hydrate(u);
    }
    if (roleId != null && !roleId.isBlank()) {
      users.removeIf(u -> !u.getRoleIds().contains(roleId));
    }
    return users;
  }

  public User get(String id) {
    User u = userMapper.selectById(id);
    if (u == null) {
      throw BizException.notFound("用户不存在");
    }
    hydrate(u);
    return u;
  }

  private void hydrate(User u) {
    u.setRoleIds(userMapper.selectRoleIds(u.getId()));
    List<String> codes = new java.util.ArrayList<>();
    if (u.getZoneCode() != null) codes.add(u.getZoneCode());
    if (u.getCompanyCode() != null) codes.add(u.getCompanyCode());
    if (u.getDeptCode() != null) codes.add(u.getDeptCode());
    if (u.getGroupCode() != null) codes.add(u.getGroupCode());
    u.setOrgCodes(codes);
    u.setFabricIdentity(identities.status(u.getId()));
  }

  // ---- CRUD ----

  @Transactional
  public User create(
      String username,
      String name,
      String phone,
      String email,
      String rawPassword,
      List<String> roleIds,
      List<String> orgCodes,
      String status,
      String blockchainId,
      String blockchainAddress) {
    if (blockchainId != null || blockchainAddress != null)
      throw BizException.badRequest("身份字段由服务端管理");
    username = username == null ? null : username.trim();
    if (username == null || username.isBlank()) {
      throw BizException.badRequest("请输入用户名");
    }
    if (username.length() < 3 || username.length() > 64) {
      throw BizException.badRequest("用户名长度需为 3-64 位");
    }
    if (!username.matches("^[A-Za-z0-9_.-]+$")) {
      throw BizException.badRequest("用户名仅支持字母、数字、下划线、点和连字符");
    }
    if (rawPassword == null || rawPassword.length() < 6) {
      throw BizException.badRequest("密码至少 6 位");
    }
    if (userMapper.countByUsername(username) > 0) {
      throw BizException.conflict("用户名已存在：" + username);
    }
    validateRoles(roleIds);
    OrgService.OrgResolveResult org = orgService.resolve(orgCodes);
    identities.validateOrganization(org.companyCode);
    LocalDateTime now = LocalDateTime.now();
    String id = java.util.UUID.randomUUID().toString().replace("-", "");
    userMapper.insert(
        id,
        username,
        passwordEncoder.encode(rawPassword),
        name,
        emptyToNull(phone),
        emptyToNull(email),
        status == null ? "active" : status,
        org.zone,
        org.company,
        org.dept,
        org.group,
        org.zoneCode,
        org.companyCode,
        org.deptCode,
        org.groupCode,
        org.orgPath,
        joinOrgCodes(orgCodes, org),
        null,
        null,
        now);
    replaceRoles(id, roleIds);
    identities.sync(id, "USER_CHANGED");
    return get(id);
  }

  @Transactional
  public User update(
      String id,
      String name,
      String phone,
      String email,
      String rawPassword,
      List<String> roleIds,
      List<String> orgCodes,
      String status,
      String blockchainId,
      String blockchainAddress,
      LoginUser current) {
    if (blockchainId != null || blockchainAddress != null)
      throw BizException.badRequest("身份字段由服务端管理");
    userMapper.lockUser(id);
    User existing = userMapper.selectById(id);
    if (existing == null) {
      throw BizException.notFound("用户不存在");
    }
    protectAdministrator(existing);
    validateRoles(roleIds);
    if (current != null && id.equals(current.getUserId()) && "disabled".equals(status)) {
      throw BizException.badRequest("不能停用当前登录账号");
    }
    OrgService.OrgResolveResult org = orgService.resolve(orgCodes);
    identities.validateOrganization(org.companyCode);
    String passwordHash = null;
    if (rawPassword != null && !rawPassword.isBlank()) {
      if (rawPassword.length() < 6) {
        throw BizException.badRequest("密码至少 6 位");
      }
      passwordHash = passwordEncoder.encode(rawPassword);
    }
    userMapper.update(
        id,
        name,
        emptyToNull(phone),
        emptyToNull(email),
        status == null ? existing.getStatus() : status,
        org.zone,
        org.company,
        org.dept,
        org.group,
        org.zoneCode,
        org.companyCode,
        org.deptCode,
        org.groupCode,
        org.orgPath,
        joinOrgCodes(orgCodes, org),
        existing.getBlockchainId(),
        existing.getBlockchainAddress(),
        passwordHash,
        LocalDateTime.now());
    sessions.revokeUser(id);
    replaceRoles(id, roleIds);
    identities.sync(id, "USER_CHANGED");
    return get(id);
  }

  @Transactional
  public void delete(String id, LoginUser current) {
    userMapper.lockUser(id);
    if (current != null && id.equals(current.getUserId())) {
      throw BizException.badRequest("不能删除当前登录账号");
    }
    if (userMapper.selectById(id) == null) {
      throw BizException.notFound("用户不存在");
    }
    protectAdministrator(userMapper.selectById(id));
    sessions.revokeUser(id);
    userMapper.logicDelete(id, LocalDateTime.now());
    identities.sync(id, "USER_DELETED");
    userMapper.deleteUserRoles(id);
  }

  @Transactional
  public User setStatus(String id, String target, LoginUser current) {
    userMapper.lockUser(id);
    User u = userMapper.selectById(id);
    if (u == null) {
      throw BizException.notFound("用户不存在");
    }
    protectAdministrator(u);
    if (!java.util.Set.of("active", "disabled").contains(target))
      throw BizException.badRequest("用户状态非法");
    if (target.equals(u.getStatus())) return get(id);
    if (current != null && id.equals(current.getUserId()) && "disabled".equals(target)) {
      throw BizException.badRequest("不能停用当前登录账号");
    }
    sessions.revokeUser(id);
    userMapper.updateStatus(id, target, LocalDateTime.now());
    identities.sync(id, "USER_STATUS");
    return get(id);
  }

  @Transactional
  public void resetPassword(String id, String rawPassword) {
    userMapper.lockUser(id);
    if (userMapper.selectById(id) == null) {
      throw BizException.notFound("用户不存在");
    }
    protectAdministrator(userMapper.selectById(id));
    if (rawPassword == null || rawPassword.length() < 6) {
      throw BizException.badRequest("密码至少 6 位");
    }
    sessions.revokeUser(id);
    userMapper.updatePassword(id, passwordEncoder.encode(rawPassword), LocalDateTime.now());
    identities.audit(id, "PASSWORD_RESET");
  }

  @Transactional
  public User assignRoles(String id, List<String> roleIds) {
    userMapper.lockUser(id);
    if (userMapper.selectById(id) == null) {
      throw BizException.notFound("用户不存在");
    }
    protectAdministrator(userMapper.selectById(id));
    validateRoles(roleIds);
    sessions.revokeUser(id);
    replaceRoles(id, roleIds);
    identities.sync(id, "USER_CHANGED");
    return get(id);
  }

  // ---- 认证加载 ----

  @Override
  public LoginUser load(String userId) {
    User u = userMapper.selectById(userId);
    if (u == null || !"active".equals(u.getStatus())) {
      return null;
    }
    List<String> roleIds = userMapper.selectRoleIds(userId);
    boolean superAdmin = false;
    for (String rid : roleIds) {
      Role role = roleMapper.selectById(rid);
      if (role != null
          && "active".equals(role.getStatus())
          && "super_admin".equals(role.getCode())) {
        superAdmin = true;
        break;
      }
    }
    Set<String> perms = permissionService.codesByRoleIds(roleIds);
    return new LoginUser(u.getId(), u.getUsername(), u.getName(), superAdmin, perms);
  }

  /** 登录成功后组装用户拥有的完整角色对象（前端需要角色名展示）；停用角色不返回，其权限本就不生效。 */
  public List<Role> loadRoles(String userId) {
    List<String> roleIds = userMapper.selectRoleIds(userId);
    return roleIds.stream()
        .map(roleMapper::selectById)
        .filter(java.util.Objects::nonNull)
        .filter(r -> "active".equals(r.getStatus()))
        .toList();
  }

  public void touchLastLogin(String userId) {
    userMapper.updateLastLogin(userId, LocalDateTime.now());
  }

  /** 供 AuthService 做密码校验 */
  public java.util.Map<String, Object> authRow(String username) {
    return userMapper.selectAuthByUsername(username);
  }

  public User byUsername(String username) {
    var row = userMapper.selectAuthByUsername(username);
    if (row == null) {
      return null;
    }
    return get((String) row.get("id"));
  }

  // ---- 内部 ----

  private void protectAdministrator(User user) {
    if (user != null
        && userMapper.selectRoleIds(user.getId()).stream()
            .map(roleMapper::selectById)
            .filter(java.util.Objects::nonNull)
            .anyMatch(role -> "super_admin".equals(role.getCode())))
      throw BizException.badRequest("内置管理员账号必须通过受控维护流程变更");
  }

  private void replaceRoles(String userId, List<String> roleIds) {
    List<String> ids = roleIds == null ? List.of() : roleIds.stream().distinct().toList();
    userMapper.deleteUserRoles(userId);
    if (!ids.isEmpty()) {
      userMapper.insertUserRoles(userId, ids);
    }
  }

  private void validateRoles(List<String> roleIds) {
    if (roleIds == null || roleIds.isEmpty()) {
      throw BizException.badRequest("请至少选择一个角色");
    }
    Set<String> seen = new HashSet<>();
    for (String rid : roleIds) {
      Role role = roleMapper.selectById(rid);
      if (role == null) {
        throw BizException.badRequest("角色不存在：" + rid);
      }
      if (!"active".equals(role.getStatus())) {
        throw BizException.badRequest("角色「" + role.getName() + "」已停用，不能分配");
      }
      var actor = com.bdemo.iam.security.JwtAuthFilter.currentUser();
      if ("super_admin".equals(role.getCode()) && (actor == null || !actor.isSuperAdmin()))
        throw BizException.forbidden("仅系统管理员可授予管理员角色");
      seen.add(rid);
    }
  }

  private String emptyToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  /**
   * 拼接 org_codes 冗余列（逗号分隔，无空格），与种子格式及 zone/company/dept/group 四个 code 列严格同源， 不直接信任入参（非法组织编码在
   * resolve 阶段会被拒绝）。
   */
  private String joinOrgCodes(List<String> requested, OrgService.OrgResolveResult org) {
    List<String> codes = new java.util.ArrayList<>();
    if (org.zoneCode != null) codes.add(org.zoneCode);
    if (org.companyCode != null) codes.add(org.companyCode);
    if (org.deptCode != null) codes.add(org.deptCode);
    if (org.groupCode != null) codes.add(org.groupCode);
    return codes.isEmpty() ? null : String.join(",", codes);
  }
}
