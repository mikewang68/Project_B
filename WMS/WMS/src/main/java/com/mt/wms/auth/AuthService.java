package com.mt.wms.auth;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
public class AuthService {
    private static final String LEGACY_SALT = "+SomeSaltToFoodIsDelicious!";

    private final AuthRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final TenantContextService tenantContextService;

    public AuthService(AuthRepository repository, PasswordEncoder passwordEncoder,
                       TenantContextService tenantContextService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.tenantContextService = tenantContextService;
    }

    @Transactional
    public WmsPrincipal authenticate(String company, String account, String rawPassword) {
        AuthRepository.LoginAccount user = repository.findLoginAccount(company.trim(), account.trim())
                .orElseThrow(() -> new BadCredentialsException("公司、账号或密码不正确"));
        if (!passwordMatches(rawPassword, user.passwordHash())) {
            throw new BadCredentialsException("公司、账号或密码不正确");
        }
        if (!user.passwordHash().startsWith("$2")) {
            repository.updatePassword(user.userId(), passwordEncoder.encode(rawPassword), true);
        }
        List<String> roles = List.copyOf(repository.findRoles(user.userId()));
        Set<String> permissions = repository.findPermissions(user.userId());
        repository.markLoginSuccess(user.userId());
        return new WmsPrincipal(user.userId(), user.companyId(), user.companyCode(), user.companyName(),
                user.username(), user.displayName(), roles, permissions, user.passwordChangeRequired());
    }

    AuthModels.CurrentUserView currentUser(WmsPrincipal principal, jakarta.servlet.http.HttpSession session) {
        return new AuthModels.CurrentUserView(principal.userId(), principal.username(), principal.displayName(),
                principal.companyCode(), principal.companyName(), principal.roles(), principal.permissions(),
                principal.passwordChangeRequired(), repository.findMenus(principal.permissions()),
                tenantContextService.current(principal, session));
    }

    @Transactional
    void changePassword(WmsPrincipal principal, String currentPassword, String newPassword) {
        AuthRepository.LoginAccount account = repository.findLoginAccount(principal.companyCode(), principal.username())
                .orElseThrow(() -> new BadCredentialsException("当前账号不可用"));
        if (!passwordMatches(currentPassword, account.passwordHash())) {
            throw new BadCredentialsException("原密码不正确");
        }
        if (currentPassword.equals(newPassword)) {
            throw new IllegalArgumentException("新密码不能与原密码相同");
        }
        repository.updatePassword(principal.userId(), passwordEncoder.encode(newPassword), false);
    }

    private boolean passwordMatches(String rawPassword, String storedHash) {
        if (storedHash.startsWith("$2")) {
            return passwordEncoder.matches(rawPassword, storedHash);
        }
        return MessageDigest.isEqual(legacyHash(rawPassword).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    private String legacyHash(String rawPassword) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((rawPassword + LEGACY_SALT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持SHA-256", exception);
        }
    }
}
