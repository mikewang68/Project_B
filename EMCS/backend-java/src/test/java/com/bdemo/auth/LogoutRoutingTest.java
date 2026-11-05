package com.bdemo.auth;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.bdemo.config.SecurityConfig;
import com.bdemo.config.SecurityAuditAccessDeniedHandler;
import com.bdemo.system.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = AuthController.class, properties = {
    "b-demo.auth.captcha-enabled=false", "b-demo.auth.register-enabled=false"})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class LogoutRoutingTest {
  @Autowired MockMvc mvc;
  @MockBean JwtService jwt;
  @MockBean SystemUserMapper users;
  @MockBean SystemMenuMapper menus;
  @MockBean CaptchaService captcha;
  @MockBean SecurityAuditAccessDeniedHandler deniedHandler;
  @MockBean EmcsSsoSessions sso;

  @Test
  void logoutReachesRevocationControllerInsteadOfDefaultLoginRedirect() throws Exception {
    when(jwt.parse("test-native-token")).thenReturn(new AuthenticatedUser(42, "operator"));
    var account = mock(UserAccount.class); when(account.status()).thenReturn("0"); when(account.delFlag()).thenReturn("0");
    when(users.findById(42)).thenReturn(Optional.of(account));
    when(users.findRoleKeys(42)).thenReturn(List.of("admin"));
    when(users.findPermissions(42)).thenReturn(List.of());
    mvc.perform(post("/logout").header("Authorization", "Bearer test-native-token"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    verify(jwt).revoke("test-native-token");
  }
}
