package com.bproject.trust.logevidence;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bproject.trust.shared.web.ErrorHandler;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitConfig(LogSecurityTest.Config.class)
@WebAppConfiguration
class LogSecurityTest {
  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @Import({LogSecurity.class, ErrorHandler.class})
  static class Config {
    @Bean
    LogSettings settings() {
      return mock(LogSettings.class);
    }

    @Bean
    LogEvidenceService service() {
      return mock(LogEvidenceService.class);
    }

    @Bean
    LogEvidenceVerification verification() {
      return mock(LogEvidenceVerification.class);
    }

    @Bean
    LogIngressController controller(LogEvidenceService service) {
      return new LogIngressController(service);
    }
  }

  @Autowired WebApplicationContext context;
  @Autowired LogSettings settings;
  @Autowired LogEvidenceService service;
  MockMvc http;
  String token = "synthetic-audit-only-credential";
  LogSettings.Source source;

  @BeforeEach
  void setup() {
    reset(settings, service);
    source =
        new LogSettings.Source(
            "audit",
            LogInput.hash(token),
            "projection",
            Set.of("read", "submit", "retry"),
            Set.of(new LogSettings.Scope("D", "Z", "TRUST")));
    when(settings.read())
        .thenReturn(
            new LogSettings.Config(
                1,
                true,
                "test",
                "",
                Set.of(),
                List.of(source),
                new LogSettings.Delivery(false, "", "", Map.of())));
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void rejectsOtherCredentialsAndSessions() throws Exception {
    for (String auth : List.of("", "Bearer unrelated"))
      http.perform(get(LogSecurity.BASE + "/key").header("Authorization", auth))
          .andExpect(status().isUnauthorized());
    http.perform(
            get(LogSecurity.BASE + "/key")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("admin")
                        .roles("ADMIN")))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @Test
  void disabledDoesNotFallbackAndInvalidBodiesDoNotMutate() throws Exception {
    http.perform(
            post(LogSecurity.BASE)
                .header("Authorization", "Bearer " + token)
                .contentType("text/plain")
                .content("{}"))
        .andExpect(status().isUnsupportedMediaType());
    for (String body : List.of("", "[]", "null", "{\"a\":1}", "{} {}"))
      http.perform(
              post(LogSecurity.BASE + "/key/retry")
                  .header("Authorization", "Bearer " + token)
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest());
    when(settings.read())
        .thenReturn(
            new LogSettings.Config(
                1,
                false,
                "",
                "",
                Set.of(),
                List.of(),
                new LogSettings.Delivery(false, "", "", Map.of())));
    http.perform(get(LogSecurity.BASE + "/key")).andExpect(status().isNotFound());
    verifyNoInteractions(service);
  }

  @Test
  void receiptWireShapeAndRetryUseDedicatedRoute() throws Exception {
    when(service.receipt(source, "key"))
        .thenReturn(
            Map.of(
                "eventKey", "key", "payloadHash", "hash", "state", "PENDING", "transactionId", ""));
    http.perform(get(LogSecurity.BASE + "/key").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("PENDING"));
    when(service.retry(source, "key"))
        .thenReturn(new LogEvidenceService.Result(202, Map.of("queued", true)));
    http.perform(
            post(LogSecurity.BASE + "/key/retry")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.queued").value(true));
  }
}
