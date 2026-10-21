package com.bdemo.iam.auth;

import com.bdemo.iam.auth.dto.LoginRequest;
import com.bdemo.iam.auth.dto.LoginResponse;
import com.bdemo.iam.common.R;
import com.bdemo.iam.security.JwtAuthFilter;
import com.bdemo.iam.security.LoginUser;
import com.bdemo.iam.sso.SsoLogoutCoordinator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final SsoLogoutCoordinator ssoLogout;

    public AuthController(AuthService authService, SsoLogoutCoordinator ssoLogout) {
        this.authService = authService;
        this.ssoLogout = ssoLogout;
    }

    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return R.ok(authService.login(request, clientIp(http), http.getHeader("User-Agent")));
    }

    @GetMapping("/me")
    public R<LoginResponse> me() {
        LoginUser user = JwtAuthFilter.currentUser();
        return R.ok(authService.current(user));
    }

    /**
     * 退出登录。
     *
     * <p>除了撤销 IAM 自身会话，还要完成平台侧 SLO：
     * 撤销未消费短票、通知已建立业务会话的系统、记录投递失败以便重试。
     *
     * <p>返回值区分"本地登出成功"与"下游是否全部撤销成功"：
     * {@code downstreamComplete=false} 表示仍有待投递或投递失败的注销通知，
     * 此时本地登出仍然有效（用户已无法用旧 token 访问 IAM），
     * 但下游系统可能需要等重试完成才失效。
     */
    @PostMapping("/logout")
    public R<java.util.Map<String, Object>> logout(HttpServletRequest http) {
        LoginUser user = JwtAuthFilter.currentUser();
        String sid = SsoLogoutCoordinator.sessionIdOf(http);
        authService.logout(user, clientIp(http));
        SsoLogoutCoordinator.Result slo = ssoLogout.logout(user, sid, http);
        return R.ok(slo.toBody());
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
