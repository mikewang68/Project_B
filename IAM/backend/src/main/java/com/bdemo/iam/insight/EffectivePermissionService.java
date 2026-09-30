package com.bdemo.iam.insight;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.permission.domain.PermissionNode;
import com.bdemo.iam.permission.mapper.PermissionMapper;
import com.bdemo.iam.role.dto.RoleBrief;
import com.bdemo.iam.role.dto.RolePermissionRow;
import com.bdemo.iam.role.mapper.RoleMapper;
import com.bdemo.iam.user.domain.User;
import com.bdemo.iam.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 计算用户最终有效权限及每个权限的来源角色。
 * 全部基于 iam_user_role / iam_role / iam_role_permission / iam_permission 真实关系，不硬编码。
 */
@Service
public class EffectivePermissionService {

    private final com.bdemo.iam.security.LoginUserLoader loginUserLoader;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;

    public EffectivePermissionService(UserMapper userMapper, RoleMapper roleMapper,
                                      PermissionMapper permissionMapper, com.bdemo.iam.security.LoginUserLoader loginUserLoader) {
        this.loginUserLoader = loginUserLoader;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
    }

    /** 用户有效权限完整视图。 */
    public EffectivePermissionView getView(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        var loginUser = loginUserLoader.load(userId);
        List<RoleBrief> roles = loginUser == null ? List.of() : roleMapper.selectActiveRolesByUser(userId);

        // 权限编码 -> 名称（按钮目录）
        Map<String, String> nameByCode = new LinkedHashMap<>();
        for (PermissionNode btn : permissionMapper.selectAllButtons()) {
            nameByCode.put(btn.getCode(), btn.getName());
        }

        // 权限编码 -> 来源角色集合（批量一次取关系，避免 N+1）
        Map<String, LinkedHashSet<RoleBrief>> sources = new LinkedHashMap<>();
        List<String> roleIds = roles.stream().map(RoleBrief::getId).toList();
        if (!roleIds.isEmpty()) {
            Map<String, RoleBrief> roleById = new LinkedHashMap<>();
            for (RoleBrief rb : roles) {
                roleById.put(rb.getId(), rb);
            }
            List<RolePermissionRow> rows = roleMapper.selectRolePermissionRows(roleIds);
            for (RolePermissionRow row : rows) {
                RoleBrief rb = roleById.get(row.getRoleId());
                if (rb != null) {
                    sources.computeIfAbsent(row.getPermissionCode(), k -> new LinkedHashSet<>()).add(rb);
                }
            }
        }

        if (loginUser != null && loginUser.isSuperAdmin()) {
            var admins = roles.stream().filter(r -> "super_admin".equals(r.getCode())).toList();
            for (String code : nameByCode.keySet())
                sources.computeIfAbsent(code, k -> new LinkedHashSet<>()).addAll(admins);
        }
        sources.keySet().removeIf(code -> loginUser == null || !loginUser.hasPermission(code));
        List<EffectivePermission> permissions = new ArrayList<>();
        sources.keySet().stream().sorted().forEach(code ->
                permissions.add(new EffectivePermission(
                        code,
                        nameByCode.getOrDefault(code, code),
                        new ArrayList<>(sources.get(code)))));

        var view = new EffectivePermissionView(userId, user.getUsername(), user.getName(), roles, permissions);
        view.setEnabled(loginUser != null);
        view.setSuperAdmin(loginUser != null && loginUser.isSuperAdmin());
        return view;
    }

    /** 解释某用户为什么拥有 / 没有某权限。 */
    public PermissionExplanation explain(String userId, String code) {
        if (code == null || code.isBlank()) {
            throw BizException.badRequest("请提供权限编码");
        }
        EffectivePermissionView view = getView(userId);
        if (!view.isEnabled()) return new PermissionExplanation(code, false, List.of(), "用户已停用，所有权限均不生效");
        if (view.isSuperAdmin()) return new PermissionExplanation(code, true,
                view.getRoles().stream().filter(r -> "super_admin".equals(r.getCode())).toList(),
                "启用的超级管理员角色具有全部接口权限（包括未登记在按钮目录中的权限）");
        for (EffectivePermission p : view.getPermissions()) {
            if (code.equals(p.getPermissionCode())) {
                return new PermissionExplanation(code, true, p.getSourceRoles(), null);
            }
        }
        return new PermissionExplanation(code, false, List.of(), "当前用户的有效角色均未包含该权限");
    }
}
