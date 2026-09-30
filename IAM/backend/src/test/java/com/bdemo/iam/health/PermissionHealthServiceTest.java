package com.bdemo.iam.health;

import com.bdemo.iam.permission.domain.PermissionNode;
import com.bdemo.iam.permission.mapper.PermissionMapper;
import com.bdemo.iam.role.domain.Role;
import com.bdemo.iam.role.dto.RolePermissionRow;
import com.bdemo.iam.role.mapper.RoleMapper;
import com.bdemo.iam.user.domain.User;
import com.bdemo.iam.user.dto.UserRoleRow;
import com.bdemo.iam.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PermissionHealthService 纯单元测试：Mock Mapper + 内存 fixture，不依赖任何真实/生产数据，离线可运行。
 */
class PermissionHealthServiceTest {

    private final RoleMapper roleMapper = mock(RoleMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final PermissionMapper permissionMapper = mock(PermissionMapper.class);
    private final PermissionHealthService service =
            new PermissionHealthService(roleMapper, userMapper, permissionMapper);

    // ---------- fixtures ----------
    private Role role(String id, String code, String name, String status) {
        Role r = new Role();
        r.setId(id);
        r.setCode(code);
        r.setName(name);
        r.setStatus(status);
        return r;
    }

    private PermissionNode button(String code, String name) {
        PermissionNode p = new PermissionNode();
        p.setId("btn-" + code);
        p.setNodeType("BUTTON");
        p.setCode(code);
        p.setName(name);
        p.setSystem("iam");
        return p;
    }

    private RolePermissionRow rp(String roleId, String code) {
        RolePermissionRow row = new RolePermissionRow();
        row.setRoleId(roleId);
        row.setPermissionCode(code);
        return row;
    }

    private User user(String id, String username) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setStatus("active");
        return u;
    }

    private UserRoleRow ur(String userId, String roleId) {
        UserRoleRow row = new UserRoleRow();
        row.setUserId(userId);
        row.setRoleId(roleId);
        return row;
    }

    private void stub(List<Role> roles, List<PermissionNode> buttons, List<User> users,
                      List<RolePermissionRow> rps, List<UserRoleRow> urs) {
        when(roleMapper.selectAll()).thenReturn(roles);
        when(permissionMapper.selectAllButtons()).thenReturn(buttons);
        when(userMapper.selectAll()).thenReturn(users);
        when(roleMapper.selectAllRolePermissionRows()).thenReturn(rps);
        when(userMapper.selectAllUserRoleRows()).thenReturn(urs);
    }

    private boolean hasRule(PermissionHealthReport report, String ruleId) {
        return report.getFindings().stream().anyMatch(f -> ruleId.equals(f.getRuleId()));
    }

    private HealthFinding first(PermissionHealthReport report, String ruleId) {
        return report.getFindings().stream()
                .filter(f -> ruleId.equals(f.getRuleId())).findFirst().orElseThrow();
    }

    // ---------- 场景 ----------
    @Test
    void emptyRole_detected() {
        stub(List.of(role("r1", "temp_role", "临时角色", "active")),
                List.of(), List.of(), List.of(), List.of());
        PermissionHealthReport report = service.analyze();
        assertTrue(hasRule(report, "IAM-001"));
    }

    @Test
    void unusedPermission_detected() {
        stub(List.of(), List.of(button("iam:x:list:view", "X查看")), List.of(), List.of(), List.of());
        PermissionHealthReport report = service.analyze();
        assertTrue(hasRule(report, "IAM-002"));
        assertEquals("iam:x:list:view", first(report, "IAM-002").getEvidence().get(0));
    }

    @Test
    void overlappingRole_detected() {
        Role a = role("ra", "role_a", "角色A", "active");
        Role b = role("rb", "role_b", "角色B", "active");
        User u = user("u1", "alice");
        stub(List.of(a, b),
                List.of(button("p1", "P1"), button("p2", "P2")),
                List.of(u),
                List.of(rp("ra", "p1"), rp("rb", "p1"), rp("rb", "p2")),
                List.of(ur("u1", "ra"), ur("u1", "rb")));
        PermissionHealthReport report = service.analyze();
        assertTrue(hasRule(report, "IAM-003"));
        assertFalse(hasRule(report, "IAM-006"));
    }

    @Test
    void duplicateRole_detected() {
        Role a = role("ra", "role_a", "角色A", "active");
        Role b = role("rb", "role_b", "角色B", "active");
        User u = user("u1", "alice");
        stub(List.of(a, b), List.of(button("p1", "P1")), List.of(u),
                List.of(rp("ra", "p1"), rp("rb", "p1")),
                List.of(ur("u1", "ra"), ur("u1", "rb")));
        PermissionHealthReport report = service.analyze();
        assertTrue(hasRule(report, "IAM-006"));
        assertFalse(hasRule(report, "IAM-003"));
    }

    @Test
    void readOnlyRoleWithEdit_flaggedHigh() {
        Role v = role("rv", "viewer", "观摩用户", "active");
        User u = user("u1", "viewer01");
        stub(List.of(v), List.of(button("iam:x:edit:edit", "X编辑")), List.of(u),
                List.of(rp("rv", "iam:x:edit:edit")), List.of(ur("u1", "rv")));
        PermissionHealthReport report = service.analyze();
        HealthFinding f = first(report, "IAM-005");
        assertEquals("HIGH", f.getSeverity());
    }

    @Test
    void readOnlyRoleWithDelete_flaggedHigh() {
        Role v = role("rr", "read_only", "只读", "active");
        User u = user("u1", "ro01");
        stub(List.of(v), List.of(button("iam:x:delete:delete", "X删除")), List.of(u),
                List.of(rp("rr", "iam:x:delete:delete")), List.of(ur("u1", "rr")));
        PermissionHealthReport report = service.analyze();
        assertEquals("HIGH", first(report, "IAM-005").getSeverity());
    }

    @Test
    void normalViewer_noConflict() {
        Role v = role("rv", "viewer", "观摩用户", "active");
        User u = user("u1", "viewer01");
        stub(List.of(v), List.of(button("iam:x:list:view", "X查看")), List.of(u),
                List.of(rp("rv", "iam:x:list:view")), List.of(ur("u1", "rv")));
        PermissionHealthReport report = service.analyze();
        assertFalse(hasRule(report, "IAM-005"));
        assertFalse(hasRule(report, "IAM-001"));
    }

    @Test
    void normalAdmin_noHighFinding() {
        Role admin = role("role-super-admin", "super_admin", "系统管理员", "active");
        User u = user("user-admin", "admin");
        stub(List.of(admin),
                List.of(button("iam:user:add:add", "新增用户"), button("iam:user:delete:delete", "删除用户")),
                List.of(u),
                List.of(rp("role-super-admin", "iam:user:add:add"),
                        rp("role-super-admin", "iam:user:delete:delete")),
                List.of(ur("user-admin", "role-super-admin")));
        PermissionHealthReport report = service.analyze();
        assertTrue(report.getFindings().stream().noneMatch(f -> "HIGH".equals(f.getSeverity())));
        assertFalse(hasRule(report, "IAM-005"));
    }

    @Test
    void emptyData_noFindings() {
        stub(List.of(), List.of(), List.of(), List.of(), List.of());
        PermissionHealthReport report = service.analyze();
        assertEquals(0, report.getFindings().size());
        assertEquals(0, report.getSummary().getRoleCount());
        assertEquals(0, report.getSummary().getUserCount());
    }

    @Test
    void relationToMissingUser_isIgnored() {
        Role r = role("r1", "role_a", "角色A", "active");
        // user_role 指向一个已不存在的用户（孤儿关系）
        stub(List.of(r), List.of(), new ArrayList<>(),
                List.of(), List.of(ur("ghost-user", "r1")));
        PermissionHealthReport report = service.analyze();
        // 不应抛异常；该角色因无"真实"成员仍报 IAM-001，但不会因幽灵用户产生其他发现
        assertTrue(hasRule(report, "IAM-001"));
    }
}
