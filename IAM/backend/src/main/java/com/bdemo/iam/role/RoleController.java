package com.bdemo.iam.role;

import com.bdemo.iam.common.R;
import com.bdemo.iam.log.OperationLogger;
import com.bdemo.iam.security.JwtAuthFilter;
import com.bdemo.iam.security.RequirePerm;
import com.bdemo.iam.role.domain.Role;
import com.bdemo.iam.role.dto.AssignPermsRequest;
import com.bdemo.iam.role.dto.RoleUpsertRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/roles")
public class RoleController {

    private final RoleService roleService;
    private final OperationLogger operationLogger;

    public RoleController(RoleService roleService, OperationLogger operationLogger) {
        this.roleService = roleService;
        this.operationLogger = operationLogger;
    }

    @GetMapping
    @RequirePerm("iam:role:list:view")
    public R<List<Role>> list() {
        return R.ok(roleService.list());
    }

    @GetMapping("/{id}")
    @RequirePerm("iam:role:list:view")
    public R<Role> detail(@PathVariable String id) {
        return R.ok(roleService.get(id));
    }

    @GetMapping("/{id}/permissions")
    @RequirePerm({"iam:role:perm:view", "iam:role:perm:edit"})
    public R<List<String>> permissions(@PathVariable String id) {
        return R.ok(roleService.get(id).getPermCodes());
    }

    @PostMapping
    @RequirePerm("iam:role:add:add")
    public R<Role> create(@Valid @RequestBody RoleUpsertRequest req, HttpServletRequest http) {
        Role role = roleService.create(req.name(), req.code(), req.description(), req.permCodes(), req.status());
        operationLogger.record(me(), null, "operation", "role", "add",
                role.getCode(), "新增角色：" + role.getCode(),
                "POST", "/roles", ip(http), "success");
        return R.ok(role);
    }

    @PutMapping("/{id}")
    @RequirePerm("iam:role:edit:edit")
    public R<Role> update(@PathVariable String id, @Valid @RequestBody RoleUpsertRequest req,
                          HttpServletRequest http) {
        Role role = roleService.update(id, req.name(), req.description(), req.status());
        operationLogger.record(me(), null, "operation", "role", "edit",
                role.getCode(), "编辑角色：" + role.getCode(),
                "PUT", "/roles/" + id, ip(http), "success");
        return R.ok(role);
    }

    @PutMapping("/{id}/status")
    @RequirePerm("iam:role:edit:edit")
    public R<Role> toggleStatus(@PathVariable String id, @RequestBody StatusRequest req, HttpServletRequest http) {
        Role role = roleService.updateStatus(id, req.status());
        String actionText = "active".equals(role.getStatus()) ? "启用" : "停用";
        operationLogger.record(me(), null, "operation", "role", "status",
                role.getCode(), actionText + "角色：" + role.getCode(),
                "PUT", "/roles/" + id + "/status", ip(http), "success");
        return R.ok(role);
    }

    @PutMapping("/{id}/permissions")
    @RequirePerm({"iam:role:perm:edit", "iam:role:perm:execute"})
    public R<Role> assignPermissions(@PathVariable String id, @RequestBody AssignPermsRequest req,
                                     HttpServletRequest http) {
        Role role = roleService.assignPermissions(id, req.permCodes());
        int count = role.getPermCodes().size();
        operationLogger.record(me(), null, "operation", "role", "assignPerms",
                role.getCode(), "角色「" + role.getName() + "」分配 " + count + " 项权限",
                "PUT", "/roles/" + id + "/permissions", ip(http), "success");
        return R.ok(role);
    }

    @DeleteMapping("/{id}")
    @RequirePerm("iam:role:delete:delete")
    public R<Void> delete(@PathVariable String id, HttpServletRequest http) {
        Role role = roleService.get(id);
        roleService.delete(id);
        operationLogger.record(me(), null, "operation", "role", "delete",
                role.getCode(), "删除角色：" + role.getCode(),
                "DELETE", "/roles/" + id, ip(http), "success");
        return R.ok();
    }

    /** 启用/停用角色请求体 */
    public record StatusRequest(String status) {
    }

    private String me() {
        var u = JwtAuthFilter.currentUser();
        return u == null ? null : u.getUsername();
    }

    private String ip(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
