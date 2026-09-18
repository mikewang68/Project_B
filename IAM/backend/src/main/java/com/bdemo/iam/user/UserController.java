package com.bdemo.iam.user;

import com.bdemo.iam.common.R;
import com.bdemo.iam.log.OperationLogger;
import com.bdemo.iam.security.JwtAuthFilter;
import com.bdemo.iam.security.LoginUser;
import com.bdemo.iam.security.RequirePerm;
import com.bdemo.iam.user.domain.User;
import com.bdemo.iam.user.dto.AssignRolesRequest;
import com.bdemo.iam.user.dto.ResetPasswordRequest;
import com.bdemo.iam.user.dto.UserUpsertRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;
    private final OperationLogger operationLogger;

    public UserController(UserService userService, OperationLogger operationLogger) {
        this.userService = userService;
        this.operationLogger = operationLogger;
    }

    @GetMapping
    @RequirePerm("iam:user:list:view")
    public R<List<User>> list(@RequestParam(required = false) String keyword,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) String roleId) {
        return R.ok(userService.list(keyword, status, roleId));
    }

    @GetMapping("/{id}")
    @RequirePerm("iam:user:list:view")
    public R<User> detail(@PathVariable String id) {
        return R.ok(userService.get(id));
    }

    @PostMapping
    @RequirePerm("iam:user:add:add")
    public R<User> create(@Valid @RequestBody UserUpsertRequest req, HttpServletRequest http) {
        User user = userService.create(req.username(), req.name(), req.phone(), req.email(),
                req.password(), req.roleIds(), req.orgCodes(), req.status(),
                req.blockchainId(), req.blockchainAddress());
        operationLogger.record(currentName(), currentId(), "operation", "user", "add",
                user.getUsername(), "新增用户：" + user.getUsername(),
                "POST", "/users", clientIp(http), "success");
        return R.ok(user);
    }

    @PutMapping("/{id}")
    @RequirePerm("iam:user:edit:edit")
    public R<User> update(@PathVariable String id, @Valid @RequestBody UserUpsertRequest req,
                          HttpServletRequest http) {
        User user = userService.update(id, req.name(), req.phone(), req.email(), req.password(),
                req.roleIds(), req.orgCodes(), req.status(), req.blockchainId(),
                req.blockchainAddress(), JwtAuthFilter.currentUser());
        operationLogger.record(currentName(), currentId(), "operation", "user", "edit",
                user.getUsername(), "编辑用户：" + user.getUsername(),
                "PUT", "/users/" + id, clientIp(http), "success");
        return R.ok(user);
    }

    @DeleteMapping("/{id}")
    @RequirePerm("iam:user:delete:delete")
    public R<Void> delete(@PathVariable String id, HttpServletRequest http) {
        User user = userService.get(id);
        userService.delete(id, JwtAuthFilter.currentUser());
        operationLogger.record(currentName(), currentId(), "operation", "user", "delete",
                user.getUsername(), "删除用户：" + user.getUsername(),
                "DELETE", "/users/" + id, clientIp(http), "success");
        return R.ok();
    }

    @PutMapping("/{id}/status")
    @RequirePerm({"iam:user:delete:delete", "iam:user:delete:execute"})
    public R<User> toggleStatus(@PathVariable String id, HttpServletRequest http) {
        User user = userService.toggleStatus(id, JwtAuthFilter.currentUser());
        String actionText = "active".equals(user.getStatus()) ? "启用" : "停用";
        operationLogger.record(currentName(), currentId(), "operation", "user", "status",
                user.getUsername(), actionText + "用户：" + user.getUsername(),
                "PUT", "/users/" + id + "/status", clientIp(http), "success");
        return R.ok(user);
    }

    @PutMapping("/{id}/password")
    @RequirePerm({"iam:auth:password:edit", "iam:auth:password:execute"})
    public R<Void> resetPassword(@PathVariable String id, @Valid @RequestBody ResetPasswordRequest req,
                                 HttpServletRequest http) {
        userService.resetPassword(id, req.password());
        operationLogger.record(currentName(), currentId(), "operation", "user", "resetPassword",
                userSafeName(id), "重置用户密码：" + id,
                "PUT", "/users/" + id + "/password", clientIp(http), "success");
        return R.ok();
    }

    @PutMapping("/{id}/roles")
    @RequirePerm({"iam:user:role:edit", "iam:user:role:execute"})
    public R<User> assignRoles(@PathVariable String id, @Valid @RequestBody AssignRolesRequest req,
                               HttpServletRequest http) {
        User user = userService.assignRoles(id, req.roleIds());
        operationLogger.record(currentName(), currentId(), "operation", "user", "assignRoles",
                user.getUsername(), "分配角色：" + user.getUsername(),
                "PUT", "/users/" + id + "/roles", clientIp(http), "success");
        return R.ok(user);
    }

    private String userSafeName(String id) {
        try {
            return userService.get(id).getUsername();
        } catch (Exception e) {
            return id;
        }
    }

    private String currentName() {
        LoginUser u = JwtAuthFilter.currentUser();
        return u == null ? null : u.getUsername();
    }

    private String currentId() {
        LoginUser u = JwtAuthFilter.currentUser();
        return u == null ? null : u.getUserId();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
