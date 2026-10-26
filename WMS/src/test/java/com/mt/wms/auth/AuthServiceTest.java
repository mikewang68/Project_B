package com.mt.wms.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock AuthRepository repository;
    @Mock TenantContextService tenantContextService;

    @Test
    void upgradesLegacyPasswordAfterSuccessfulLogin() throws Exception {
        String raw = "legacy-password";
        String legacy = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((raw + "+SomeSaltToFoodIsDelicious!").getBytes(StandardCharsets.UTF_8)));
        when(repository.findLoginAccount("default", "old-user")).thenReturn(Optional.of(
                new AuthRepository.LoginAccount(7L, 1L, "default", "默认公司", "old-user", "旧用户", legacy, true)));
        when(repository.findRoles(7L)).thenReturn(List.of("NORMAL"));
        when(repository.findPermissions(7L)).thenReturn(Set.of("dashboard:view"));
        AuthService service = new AuthService(repository, new BCryptPasswordEncoder(4), tenantContextService);

        WmsPrincipal principal = service.authenticate("default", "old-user", raw);

        assertThat(principal.username()).isEqualTo("old-user");
        verify(repository).updatePassword(eq(7L), startsWith("$2"), eq(true));
        verify(repository).markLoginSuccess(7L);
    }
}
