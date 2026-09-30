package com.bdemo.iam.health;

import com.bdemo.iam.permission.domain.PermissionNode;
import com.bdemo.iam.permission.mapper.PermissionMapper;
import com.bdemo.iam.role.domain.Role;
import com.bdemo.iam.role.dto.RolePermissionRow;
import com.bdemo.iam.role.mapper.RoleMapper;
import com.bdemo.iam.user.domain.User;
import com.bdemo.iam.user.dto.UserRoleRow;
import com.bdemo.iam.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IAM Permission Health Agent —— 纯规则驱动，不调用任何 LLM。
 * 基于真实 user / role / permission / user_role / role_permission 关系做权限治理诊断，
 * 输出结构化、可解释、可验证的发现；只分析建议，不执行任何写操作（Human-in-the-loop）。
 */
@Service
public class PermissionHealthService {

    // ---- 规则常量（集中定义，不散落）----
    private static final List<String> READ_ONLY_MARKERS =
            List.of("viewer", "readonly", "read_only", "只读");
    private static final List<String> WRITE_SUFFIXES =
            List.of(":add", ":edit", ":delete");
    private static final List<String> HIGH_RISK_PATTERNS =
            List.of(":delete", "permission", ":role:add", ":role:edit", ":role:perm",
                    ":user:add", ":user:edit", "config:list:edit");
    private static final int EVIDENCE_LIMIT = 30;

    private final RoleMapper roleMapper;
    private final UserMapper userMapper;
    private final PermissionMapper permissionMapper;

    public PermissionHealthService(RoleMapper roleMapper, UserMapper userMapper,
                                   PermissionMapper permissionMapper) {
        this.roleMapper = roleMapper;
        this.userMapper = userMapper;
        this.permissionMapper = permissionMapper;
    }

    public PermissionHealthReport analyze() {
        List<Role> roles = roleMapper.selectAll();
        List<PermissionNode> buttons = permissionMapper.selectAllButtons();
        List<User> users = userMapper.selectAll();
        List<RolePermissionRow> rpRows = roleMapper.selectAllRolePermissionRows();
        List<UserRoleRow> urRows = userMapper.selectAllUserRoleRows();

        Map<String, Role> roleById = new LinkedHashMap<>();
        for (Role r : roles) {
            roleById.put(r.getId(), r);
        }
        Map<String, User> userById = new LinkedHashMap<>();
        for (User u : users) {
            userById.put(u.getId(), u);
        }

        // roleId -> 权限码（去重、保持顺序）
        Map<String, List<String>> permsByRole = new LinkedHashMap<>();
        for (Role r : roles) {
            permsByRole.put(r.getId(), new ArrayList<>());
        }
        for (RolePermissionRow row : rpRows) {
            List<String> list = permsByRole.get(row.getRoleId());
            if (list != null && !list.contains(row.getPermissionCode())) {
                list.add(row.getPermissionCode());
            }
        }

        // roleId -> userIds；userId -> roleIds
        Map<String, List<String>> usersByRole = new LinkedHashMap<>();
        Map<String, List<String>> rolesByUser = new LinkedHashMap<>();
        for (Role r : roles) {
            usersByRole.put(r.getId(), new ArrayList<>());
        }
        for (User u : users) {
            rolesByUser.put(u.getId(), new ArrayList<>());
        }
        for (UserRoleRow row : urRows) {
            // 仅统计真实存在的用户与角色，忽略孤儿关系（指向已删除用户/角色）
            List<String> m = usersByRole.get(row.getRoleId());
            if (m != null && userById.containsKey(row.getUserId()) && !m.contains(row.getUserId())) {
                m.add(row.getUserId());
            }
            List<String> rs = rolesByUser.get(row.getUserId());
            if (rs != null && roleById.containsKey(row.getRoleId()) && !rs.contains(row.getRoleId())) {
                rs.add(row.getRoleId());
            }
        }

        Set<String> referencedCodes = new LinkedHashSet<>();
        for (RolePermissionRow row : rpRows) {
            referencedCodes.add(row.getPermissionCode());
        }

        List<HealthFinding> findings = new ArrayList<>();
        ruleEmptyRole(roles, usersByRole, findings);
        ruleUnusedPermission(buttons, referencedCodes, findings);
        ruleReadOnlyConflict(roles, permsByRole, findings);
        ruleHighRisk(roles, permsByRole, findings);
        ruleOverlapAndDuplicate(users, rolesByUser, roleById, permsByRole, findings);

        findings.sort(Comparator.comparingInt(PermissionHealthService::severityRank)
                .thenComparing(HealthFinding::getRuleId)
                .thenComparing(HealthFinding::getTitle));

        HealthSummary summary = new HealthSummary();
        summary.setRoleCount(roles.size());
        summary.setPermissionCount(buttons.size());
        summary.setUserCount(users.size());
        summary.setFindingCount(findings.size());
        summary.setHigh(countSeverity(findings, "HIGH"));
        summary.setMedium(countSeverity(findings, "MEDIUM"));
        summary.setLow(countSeverity(findings, "LOW"));
        return new PermissionHealthReport(summary, findings);
    }

    // ---- IAM-001 无成员角色 ----
    private void ruleEmptyRole(List<Role> roles, Map<String, List<String>> usersByRole,
                               List<HealthFinding> findings) {
        for (Role r : roles) {
            if (!"active".equals(r.getStatus())) {
                continue;
            }
            List<String> members = usersByRole.get(r.getId());
            if (members == null || members.isEmpty()) {
                findings.add(new HealthFinding(
                        "IAM-001", "LOW", "EMPTY_ROLE", "角色没有任何成员",
                        "角色 " + r.getCode() + "（" + nz(r.getName()) + "）当前未分配给任何用户。",
                        List.of(r.getCode(), nz(r.getName())),
                        "确认该角色是否仍需要保留；如不再使用可停用或删除。"));
            }
        }
    }

    // ---- IAM-002 无引用权限（只看叶子按钮，父节点天然不直接赋权，避免误报）----
    private void ruleUnusedPermission(List<PermissionNode> buttons, Set<String> referencedCodes,
                                      List<HealthFinding> findings) {
        for (PermissionNode b : buttons) {
            if (!referencedCodes.contains(b.getCode())) {
                findings.add(new HealthFinding(
                        "IAM-002", "LOW", "UNUSED_PERMISSION", "权限未被任何角色引用",
                        "权限 " + b.getCode() + "（" + nz(b.getName()) + "）未被任何角色使用。",
                        List.of(b.getCode(), nz(b.getName()), nz(b.getSystem())),
                        "确认该权限是否为预留能力；长期不用可在权限目录标记停用。"));
            }
        }
    }

    // ---- IAM-005 只读角色拥有写权限 ----
    private void ruleReadOnlyConflict(List<Role> roles, Map<String, List<String>> permsByRole,
                                      List<HealthFinding> findings) {
        for (Role r : roles) {
            if (!"active".equals(r.getStatus())) {
                continue;
            }
            String hay = (nz(r.getCode()) + " " + nz(r.getName())).toLowerCase();
            boolean looksReadOnly = READ_ONLY_MARKERS.stream().anyMatch(hay::contains);
            if (!looksReadOnly) {
                continue;
            }
            List<String> writes = permsByRole.get(r.getId()).stream()
                    .filter(c -> WRITE_SUFFIXES.stream().anyMatch(c::endsWith))
                    .distinct().sorted().toList();
            if (!writes.isEmpty()) {
                List<String> evidence = new ArrayList<>();
                evidence.add(r.getCode());
                evidence.add(nz(r.getName()));
                evidence.addAll(writes);
                findings.add(new HealthFinding(
                        "IAM-005", "HIGH", "READ_ONLY_CONFLICT", "只读角色包含写权限",
                        "角色 " + r.getCode() + " 命名呈只读语义，却包含 " + writes.size() + " 个写权限。",
                        evidence,
                        "核对角色定位，移除写权限或调整角色命名，避免权限语义冲突。"));
            }
        }
    }

    // ---- IAM-004 高风险权限盘点（信息性）----
    private void ruleHighRisk(List<Role> roles, Map<String, List<String>> permsByRole,
                              List<HealthFinding> findings) {
        Map<String, LinkedHashSet<String>> riskSources = new LinkedHashMap<>();
        for (Role r : roles) {
            if (!"active".equals(r.getStatus())) {
                continue;
            }
            for (String c : permsByRole.get(r.getId())) {
                boolean risky = HIGH_RISK_PATTERNS.stream().anyMatch(c::contains);
                if (risky) {
                    riskSources.computeIfAbsent(c, k -> new LinkedHashSet<>()).add(r.getCode());
                }
            }
        }
        if (!riskSources.isEmpty()) {
            List<String> evidence = new ArrayList<>();
            riskSources.keySet().stream().sorted().limit(EVIDENCE_LIMIT).forEach(c ->
                    evidence.add(c + "（来源: " + String.join(",", riskSources.get(c)) + "）"));
            findings.add(new HealthFinding(
                    "IAM-004", "LOW", "HIGH_RISK_PERMISSION", "高风险权限盘点",
                    "当前共有 " + riskSources.size() + " 个高风险权限（删除/权限/角色/用户/配置修改类）。",
                    evidence,
                    "定期复核这些权限的授予是否必要，遵循最小权限原则。"));
        }
    }

    // ---- IAM-003 高度重叠角色 + IAM-006 完全重复角色（按用户）----
    private void ruleOverlapAndDuplicate(List<User> users, Map<String, List<String>> rolesByUser,
                                         Map<String, Role> roleById,
                                         Map<String, List<String>> permsByRole,
                                         List<HealthFinding> findings) {
        for (User u : users) {
            List<String> rids = rolesByUser.get(u.getId()).stream()
                    .filter(id -> {
                        Role r = roleById.get(id);
                        return r != null && "active".equals(r.getStatus());
                    }).toList();
            for (int i = 0; i < rids.size(); i++) {
                for (int j = i + 1; j < rids.size(); j++) {
                    String a = rids.get(i);
                    String b = rids.get(j);
                    Set<String> pa = new HashSet<>(permsByRole.get(a));
                    Set<String> pb = new HashSet<>(permsByRole.get(b));
                    if (pa.isEmpty() || pb.isEmpty()) {
                        continue;
                    }
                    Role ra = roleById.get(a);
                    Role rb = roleById.get(b);
                    if (pa.equals(pb)) {
                        findings.add(new HealthFinding(
                                "IAM-006", "LOW", "DUPLICATE_ROLE", "用户角色权限完全重复",
                                "用户 " + nz(u.getUsername()) + " 的角色 " + ra.getCode()
                                        + " 与 " + rb.getCode() + " 权限集合完全相同。",
                                List.of(nz(u.getUsername()), ra.getCode(), rb.getCode()),
                                "两个角色权限完全相同，确认是否重复配置；不会自动变更。"));
                    } else if (pb.containsAll(pa)) {
                        findings.add(overlap(u.getUsername(), ra.getCode(), rb.getCode()));
                    } else if (pa.containsAll(pb)) {
                        findings.add(overlap(u.getUsername(), rb.getCode(), ra.getCode()));
                    }
                }
            }
        }
    }

    private HealthFinding overlap(String username, String smaller, String bigger) {
        return new HealthFinding(
                "IAM-003", "LOW", "OVERLAPPING_ROLE", "角色对该用户可能冗余",
                "用户 " + nz(username) + " 的角色 " + smaller + " 权限是 " + bigger + " 的子集，无独有权限。",
                List.of(nz(username), smaller, bigger),
                "可考虑仅保留 " + bigger + "；需人工确认，不会自动变更。");
    }

    private static int severityRank(HealthFinding f) {
        return switch (f.getSeverity()) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }

    private static int countSeverity(List<HealthFinding> findings, String severity) {
        return (int) findings.stream().filter(f -> severity.equals(f.getSeverity())).count();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
