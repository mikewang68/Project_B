package com.bdemo.iam.role;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.permission.domain.PermissionNode;
import com.bdemo.iam.permission.mapper.PermissionMapper;
import com.bdemo.iam.role.domain.Role;
import com.bdemo.iam.role.mapper.RoleMapper;
import com.bdemo.iam.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class RoleService {

    private final RoleMapper roleMapper;
    private final UserMapper userMapper;
    private final PermissionMapper permissionMapper;

    public RoleService(RoleMapper roleMapper, UserMapper userMapper, PermissionMapper permissionMapper) {
        this.roleMapper = roleMapper;
        this.userMapper = userMapper;
        this.permissionMapper = permissionMapper;
    }

    public List<Role> list() {
        List<Role> roles = roleMapper.selectAll();
        roles.forEach(r -> r.setPermCodes(roleMapper.selectPermissionCodes(r.getId())));
        return roles;
    }

    public Role get(String id) {
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw BizException.notFound("角色不存在");
        }
        role.setPermCodes(roleMapper.selectPermissionCodes(id));
        return role;
    }

    @Transactional
    public Role create(String name, String code, String description, List<String> permCodes, String status) {
        if (roleMapper.countByCode(code) > 0) {
            throw BizException.conflict("角色编码已存在：" + code);
        }
        validatePermCodes(permCodes);
        LocalDateTime now = LocalDateTime.now();
        String id = "r" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(10, 99);
        roleMapper.insert(id, code, name, description, status == null ? "active" : status, now);
        if (permCodes != null && !permCodes.isEmpty()) {
            roleMapper.insertRolePermissions(id, permCodes.stream().distinct().toList());
        }
        return get(id);
    }

    @Transactional
    public Role update(String id, String name, String description, String status) {
        Role existing = mustExist(id);
        roleMapper.update(id, name, description, status == null ? existing.getStatus() : status, LocalDateTime.now());
        return get(id);
    }

    @Transactional
    public Role updateStatus(String id, String status) {
        mustExist(id);
        String target = "inactive".equals(status) ? "inactive" : "active";
        roleMapper.updateStatus(id, target, LocalDateTime.now());
        return get(id);
    }

    @Transactional
    public void delete(String id) {
        Role existing = mustExist(id);
        if ("super_admin".equals(existing.getCode())) {
            throw BizException.badRequest("内置系统管理员角色不可删除");
        }
        List<String> users = userMapper.selectUserIdsByRole(id);
        if (users != null && !users.isEmpty()) {
            throw BizException.badRequest("该角色仍被用户使用，无法删除");
        }
        LocalDateTime now = LocalDateTime.now();
        roleMapper.logicDelete(id, now);
        roleMapper.deleteRolePermissions(id);
    }

    @Transactional
    public Role assignPermissions(String roleId, List<String> permCodes) {
        Role role = mustExist(roleId);
        if ("super_admin".equals(role.getCode())) {
            throw BizException.badRequest("内置管理员角色权限由系统内置，不允许修改");
        }
        validatePermCodes(permCodes);
        List<String> codes = permCodes == null ? List.of() : permCodes.stream().distinct().sorted().toList();
        roleMapper.deleteRolePermissions(roleId);
        if (!codes.isEmpty()) {
            roleMapper.insertRolePermissions(roleId, codes);
        }
        return get(roleId);
    }

    private Role mustExist(String id) {
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw BizException.notFound("角色不存在");
        }
        return role;
    }

    private void validatePermCodes(List<String> permCodes) {
        if (permCodes == null || permCodes.isEmpty()) {
            return;
        }
        Set<String> catalog = new HashSet<>();
        for (PermissionNode btn : permissionMapper.selectAllButtons()) {
            catalog.add(btn.getCode());
        }
        for (String code : permCodes) {
            if (!catalog.contains(code)) {
                throw BizException.badRequest("权限编码不存在于权限目录：" + code);
            }
        }
    }
}
