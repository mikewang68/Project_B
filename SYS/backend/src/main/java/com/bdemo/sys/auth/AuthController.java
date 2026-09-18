package com.bdemo.sys.auth;

import com.bdemo.sys.common.BizException;
import com.bdemo.sys.common.R;
import com.bdemo.sys.identity.IdentityService;
import com.bdemo.sys.security.JwtAuthFilter;
import com.bdemo.sys.security.LoginUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * SYS 不提供登录接口：前端登录直接调用 IAM /api/v1/iam/auth/login，
 * SYS 仅提供 /auth/me 用于凭 IAM 签发的 JWT 恢复会话与权限。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final IdentityService identityService;

    public AuthController(IdentityService identityService) {
        this.identityService = identityService;
    }

    @GetMapping("/me")
    public R<Map<String, Object>> me() {
        LoginUser user = JwtAuthFilter.currentUser();
        if (user == null) {
            throw BizException.unauthorized("未登录或登录已过期");
        }
        List<Map<String, Object>> roles = identityService.loadRoles(user.getUserId());
        Object me = Map.of(
                "id", user.getUserId(),
                "username", user.getUsername(),
                "name", user.getDisplayName());
        return R.ok(Map.of(
                "user", me,
                "roles", roles,
                "permissions", user.getPermissions().stream().sorted().toList(),
                "superAdmin", user.isSuperAdmin()));
    }
}
