package com.mt.wms.auth;

import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final TenantContextService tenantContextService;
    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy securityContextHolderStrategy = SecurityContextHolder.getContextHolderStrategy();

    public AuthController(AuthService authService, TenantContextService tenantContextService,
                          SecurityContextRepository securityContextRepository) {
        this.authService = authService;
        this.tenantContextService = tenantContextService;
        this.securityContextRepository = securityContextRepository;
    }

    @GetMapping("/csrf")
    ApiResponse<AuthModels.CsrfView> csrf(CsrfToken token, HttpServletRequest request) {
        return ApiResponse.ok(new AuthModels.CsrfView(token.getHeaderName(), token.getParameterName(), token.getToken()), requestId(request));
    }

    @PostMapping("/login")
    ApiResponse<AuthModels.CurrentUserView> login(@Valid @RequestBody AuthModels.LoginRequest body,
                                                  HttpServletRequest request, HttpServletResponse response) {
        WmsPrincipal principal = authService.authenticate(body.company(), body.username(), body.password());
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(authentication);
        securityContextHolderStrategy.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return ApiResponse.ok(authService.currentUser(principal, session), requestId(request));
    }

    @GetMapping("/me")
    ApiResponse<AuthModels.CurrentUserView> me(Authentication authentication, HttpSession session,
                                                HttpServletRequest request) {
        WmsPrincipal principal = principal(authentication);
        return ApiResponse.ok(authService.currentUser(principal, session), requestId(request));
    }

    @GetMapping("/tenant")
    ApiResponse<AuthModels.TenantView> tenant(Authentication authentication, HttpSession session,
                                               HttpServletRequest request) {
        return ApiResponse.ok(tenantContextService.current(principal(authentication), session), requestId(request));
    }

    @PutMapping("/tenant")
    ApiResponse<AuthModels.TenantView> switchTenant(@Valid @RequestBody AuthModels.TenantSwitchRequest body,
                                                     Authentication authentication, HttpSession session,
                                                     HttpServletRequest request) {
        return ApiResponse.ok(tenantContextService.switchTo(principal(authentication), session,
                body.warehouseCode(), body.ownerCode()), requestId(request));
    }

    @PutMapping("/password")
    ApiResponse<Void> changePassword(@Valid @RequestBody AuthModels.ChangePasswordRequest body,
                                     Authentication authentication, HttpServletRequest request,
                                     HttpServletResponse response) {
        WmsPrincipal principal = principal(authentication);
        authService.changePassword(principal, body.currentPassword(), body.newPassword());
        WmsPrincipal updated = new WmsPrincipal(principal.userId(), principal.companyId(), principal.companyCode(),
                principal.companyName(), principal.username(), principal.displayName(), principal.roles(),
                principal.permissions(), false);
        Authentication updatedAuthentication = UsernamePasswordAuthenticationToken.authenticated(
                updated, null, updated.getAuthorities());
        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(updatedAuthentication);
        securityContextHolderStrategy.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return ApiResponse.ok(null, requestId(request));
    }

    private WmsPrincipal principal(Authentication authentication) {
        return (WmsPrincipal) authentication.getPrincipal();
    }

    private String requestId(HttpServletRequest request) {
        return request.getAttribute(RequestIdFilter.ATTRIBUTE).toString();
    }
}
