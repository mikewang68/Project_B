package com.bdemo.auth;

import com.bdemo.common.AjaxResult;
import com.bdemo.system.RouterTreeBuilder;
import com.bdemo.system.SystemMenuMapper;
import com.bdemo.system.SystemUserMapper;
import com.bdemo.system.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class AuthController {
    // REQ-071: authentication, role permissions and role-scoped dynamic menus.
    private final boolean captchaEnabled;
    private final boolean registerEnabled;
    private final CaptchaService captchaService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final SystemUserMapper userMapper;
    private final SystemMenuMapper menuMapper;

    public AuthController(
            @Value("${b-demo.auth.captcha-enabled}") boolean captchaEnabled,
            @Value("${b-demo.auth.register-enabled}") boolean registerEnabled,
            CaptchaService captchaService,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            SystemUserMapper userMapper,
            SystemMenuMapper menuMapper) {
        this.captchaEnabled = captchaEnabled;
        this.registerEnabled = registerEnabled;
        this.captchaService = captchaService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.menuMapper = menuMapper;
    }

    @GetMapping("/captchaImage")
    public AjaxResult captchaImage() {
        AjaxResult result = AjaxResult.success()
                .add("captchaEnabled", captchaEnabled)
                .add("registerEnabled", registerEnabled);
        if (captchaEnabled) {
            CaptchaService.Captcha captcha = captchaService.create();
            result.add("uuid", captcha.uuid()).add("img", captcha.base64Image());
        }
        return result;
    }

    @PostMapping(path = "/login", consumes = "application/x-www-form-urlencoded")
    public AjaxResult login(
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String uuid,
            HttpServletRequest request) {
        if (captchaEnabled && !captchaService.consume(uuid, code)) {
            return AjaxResult.error(500, "验证码错误或已失效");
        }
        UserAccount user = userMapper.findByUserName(username)
                .filter(account -> "0".equals(account.status()))
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        if (!passwordEncoder.matches(password, user.password())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        userMapper.updateLogin(user.userId(), request.getRemoteAddr());
        return AjaxResult.success().add("msg", "登录成功")
                .add("token", jwtService.create(user.userId(), user.userName()));
    }

    @GetMapping("/getInfo")
    public AjaxResult getInfo(@AuthenticationPrincipal AuthenticatedUser principal) {
        UserAccount user = userMapper.findById(principal.userId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        List<String> roles = userMapper.findRoleKeys(user.userId());
        List<String> permissions = roles.contains("admin") ? List.of("*:*:*") : userMapper.findPermissions(user.userId());
        Map<String, Object> userView = Map.of(
                "userId", user.userId(),
                "userName", user.userName(),
                "nickName", user.nickName(),
                "avatar", user.avatar() == null ? "" : user.avatar());
        return AjaxResult.success()
                .add("user", userView)
                .add("roles", roles)
                .add("permissions", permissions)
                .add("isDefaultModifyPwd", false)
                .add("isPasswordExpired", false);
    }

    @GetMapping("/getRouters")
    public AjaxResult getRouters(@AuthenticationPrincipal AuthenticatedUser principal) {
        return AjaxResult.success(RouterTreeBuilder.build(menuMapper.findRouterMenus(principal.userId())));
    }

    @PostMapping("/logout")
    public AjaxResult logout() {
        return AjaxResult.success();
    }
}
