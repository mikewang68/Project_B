package com.bdemo.iam.permission;

import com.bdemo.iam.permission.domain.PermissionNode;
import com.bdemo.iam.permission.mapper.PermissionMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PermissionService {

    private static final List<String> OP_ORDER =
            List.of("view", "add", "edit", "delete", "execute", "approve", "import", "export");

    private final PermissionMapper permissionMapper;

    public PermissionService(PermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    /**
     * 组装与前端 MenuNode 对齐的权限树。
     */
    public List<PermissionNode> tree() {
        List<PermissionNode> all = permissionMapper.selectAllNodes();
        Map<String, PermissionNode> byId = new LinkedHashMap<>();
        Map<String, List<PermissionNode>> buttonsByParent = new LinkedHashMap<>();
        List<PermissionNode> roots = new ArrayList<>();

        for (PermissionNode node : all) {
            if ("BUTTON".equals(node.getNodeType())) {
                buttonsByParent.computeIfAbsent(node.getParentId(), k -> new ArrayList<>()).add(node);
            } else {
                byId.put(node.getId(), node);
            }
        }

        for (PermissionNode leaf : byId.values()) {
            List<PermissionNode> buttons = buttonsByParent.get(leaf.getId());
            if (buttons != null && !buttons.isEmpty()) {
                // 按约定操作顺序输出 perms：{ view: 'xxx:view', add: 'xxx:add', ... }
                Map<String, String> perms = new LinkedHashMap<>();
                List<PermissionNode> sorted = new ArrayList<>(buttons);
                sorted.sort((a, b) -> {
                    int ia = opIndex(a.getCode());
                    int ib = opIndex(b.getCode());
                    return ia != ib ? Integer.compare(ia, ib) : a.getCode().compareTo(b.getCode());
                });
                for (PermissionNode btn : sorted) {
                    perms.put(opOf(btn.getCode()), btn.getCode());
                }
                leaf.setPerms(perms);
            }
        }

        for (PermissionNode node : byId.values()) {
            String pid = node.getParentId();
            if (pid == null || pid.isBlank() || !byId.containsKey(pid)) {
                roots.add(node);
            } else {
                byId.get(pid).addChild(node);
            }
        }
        return roots;
    }

    /**
     * 用户有效角色（启用、未删除）对应的权限编码并集。
     */
    public Set<String> codesByRoleIds(List<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(permissionMapper.selectCodesByRoleIds(roleIds));
    }

    private String opOf(String code) {
        int idx = code.lastIndexOf(':');
        return idx < 0 ? code : code.substring(idx + 1);
    }

    private int opIndex(String code) {
        int idx = OP_ORDER.indexOf(opOf(code));
        return idx < 0 ? OP_ORDER.size() : idx;
    }
}
