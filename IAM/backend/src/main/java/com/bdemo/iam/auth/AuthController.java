package com.bdemo.iam.auth;

import com.bdemo.iam.auth.dto.LoginRequest;
import com.bdemo.iam.auth.dto.LoginResponse;
import com.bdemo.iam.common.R;
import com.bdemo.iam.security.JwtAuthFilter;
import com.bdemo.iam.security.LoginUser;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
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

    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest http) {
        authService.logout(JwtAuthFilter.currentUser(), clientIp(http));
        return R.ok();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
