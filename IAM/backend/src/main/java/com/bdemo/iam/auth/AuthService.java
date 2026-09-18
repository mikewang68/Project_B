package com.bdemo.iam.auth;

import com.bdemo.iam.auth.dto.LoginRequest;
import com.bdemo.iam.auth.dto.LoginResponse;
import com.bdemo.iam.common.BizException;
import com.bdemo.iam.log.OperationLogger;
import com.bdemo.iam.role.domain.Role;
import com.bdemo.iam.security.JwtService;
import com.bdemo.iam.security.LoginUser;
import com.bdemo.iam.user.UserService;
import com.bdemo.iam.user.domain.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AuthService {

    private final UserService userService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final OperationLogger operationLogger;

    public AuthService(UserService userService, JwtService jwtService,
                       PasswordEncoder passwordEncoder, OperationLogger operationLogger) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.operationLogger = operationLogger;
    }

    public LoginResponse login(LoginRequest request, String ip, String userAgent) {
        String username = request.username() == null ? "" : request.username().trim();
        Map<String, Object> row = userService.authRow(username);
        if (row == null) {
            operationLogger.record(username, null, "login", "auth", "login",
                    username, "用户名不存在或密码错误", "POST", "/auth/login", ip, "fail");
            throw BizException.badRequest("用户名或密码错误");
        }
        String hash = (String) row.get("passwordHash");
        String status = (String) row.get("status");
        String id = (String) row.get("id");
        if (!"active".equals(status)) {
            operationLogger.record(username, id, "login", "auth", "login",
                    username, "账号已停用", "POST", "/auth/login", ip, "fail");
            throw BizException.badRequest("账号已停用，请联系管理员");
        }
        if (hash == null || !passwordEncoder.matches(request.password(), hash)) {
            operationLogger.record(username, id, "login", "auth", "login",
                    username, "用户名或密码错误", "POST", "/auth/login", ip, "fail");
            throw BizException.badRequest("用户名或密码错误");
        }

        User user = userService.get(id);
        List<Role> roles = userService.loadRoles(id);
        LoginUser loginUser = userService.load(id);
        List<String> permissions = loginUser == null
                ? List.of()
                : loginUser.getPermissions().stream().sorted().toList();
        String token = jwtService.generateToken(id, username);
        userService.touchLastLogin(id);
        operationLogger.record(username, id, "login", "auth", "login",
                username, "登录成功", "POST", "/auth/login", ip, "success");
        return new LoginResponse(token, user, roles, permissions);
    }

    public LoginResponse current(LoginUser loginUser) {
        if (loginUser == null) {
            throw BizException.unauthorized("未登录或登录已过期");
        }
        User user = userService.get(loginUser.getUserId());
        List<Role> roles = userService.loadRoles(loginUser.getUserId());
        List<String> permissions = loginUser.getPermissions().stream().sorted().toList();
        return new LoginResponse(null, user, roles, permissions);
    }

    public void logout(LoginUser loginUser, String ip) {
        if (loginUser != null) {
            operationLogger.record(loginUser.getUsername(), loginUser.getUserId(),
                    "login", "auth", "logout", loginUser.getUsername(), "退出登录",
                    "POST", "/auth/logout", ip, "success");
        }
    }
}
