package com.bproject.trust.ingress;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bproject.trust.shared.json.Json;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitConfig(EvidenceIngressSecurityTest.Config.class)
@WebAppConfiguration
class EvidenceIngressSecurityTest {
  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @Import({EvidenceIngressSecurity.class, IngressErrorHandler.class})
  static class Config {
    @Bean
    EvidenceIngressSettings settings() {
      return mock(EvidenceIngressSettings.class);
    }

    @Bean
    GenericEventService service() {
      return mock(GenericEventService.class);
    }

    @Bean
    GenericIngressController controller(GenericEventService service) {
      return new GenericIngressController(service, "offline", true);
    }
  }

  @Autowired WebApplicationContext context;
  @Autowired EvidenceIngressSettings settings;
  @Autowired GenericEventService service;
  MockMvc http;
  String token = "synthetic-generic-source-credential";
  EvidenceIngressSettings.Source source;

  @BeforeEach
  void setup() {
    reset(settings, service);
    source =
        new EvidenceIngressSettings.Source(
            "example",
            Json.sha(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "ORG",
            "SOURCE",
            true,
            Set.of("read", "submit"),
            Set.of("MAINTENANCE_CONFIRMED"));
    when(settings.read())
        .thenReturn(new EvidenceIngressSettings.Settings(1, true, List.of(source)));
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void onlyDedicatedCredentialAndNoSessionAuthentication() throws Exception {
    for (String credential : List.of("", "Bearer wms-test", "Bearer identity-test"))
      http.perform(get(EvidenceIngressSecurity.BASE + "/item").header("Authorization", credential))
          .andExpect(status().isUnauthorized());
    http.perform(
            get(EvidenceIngressSecurity.BASE + "/item")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("admin")
                        .roles("ADMIN")))
        .andExpect(status().isUnauthorized());
    http.perform(
            get(EvidenceIngressSecurity.BASE + "/capabilities")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.instanceId").value("offline"));
    verifyNoInteractions(service);
  }

  @Test
  void defaultsClosedAndConfigurationErrorsDoNotFallback() throws Exception {
    when(settings.read()).thenReturn(new EvidenceIngressSettings.Settings(1, false, List.of()));
    http.perform(post(EvidenceIngressSecurity.BASE)).andExpect(status().isNotFound());
    when(settings.read()).thenThrow(new IllegalStateException("private value"));
    http.perform(get(EvidenceIngressSecurity.BASE + "/item"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("INGRESS_CONFIGURATION_UNAVAILABLE"));
    verifyNoInteractions(service);
  }

  @Test
  void rejectsRetryWithoutPermissionAndRejectsWrongBodyBeforeService() throws Exception {
    http.perform(
            post(EvidenceIngressSecurity.BASE + "/item/retry")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
    http.perform(
            post(EvidenceIngressSecurity.BASE)
                .header("Authorization", "Bearer " + token)
                .contentType("text/plain")
                .content("{}"))
        .andExpect(status().isUnsupportedMediaType());
    http.perform(
            post(EvidenceIngressSecurity.BASE)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(
                    GenericEventInputTest.body("{\"value\":1}")
                        .replace("\"payload\"", "\"batchId\":\"fake\",\"payload\"")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  private void allowRetry() {
    source =
        new EvidenceIngressSettings.Source(
            source.id(),
            source.tokenSha256(),
            source.orgId(),
            source.sourceSystem(),
            true,
            Set.of("read", "submit", "retry"),
            source.allowedEventTypes());
    when(settings.read())
        .thenReturn(new EvidenceIngressSettings.Settings(1, true, List.of(source)));
  }

  @Test
  void retryAcceptsEmptyObjectWhitespaceAndEmptyBodyThroughSecurityChain() throws Exception {
    allowRetry();
    when(service.retry(source, "item"))
        .thenReturn(new GenericEventService.Result(202, Map.of("queued", true)));
    var bodies =
        List.of("", " \r\n\t", "{}", "{ }", "{\n\t\r }", " \n{ }\r\n", "{" + " ".repeat(62) + "}");
    for (String body : bodies)
      http.perform(
              post(EvidenceIngressSecurity.BASE + "/item/retry")
                  .header("Authorization", "Bearer " + token)
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.queued").value(true));
    verify(service, times(bodies.size())).retry(source, "item");
    verifyNoMoreInteractions(service);
  }

  @Test
  void retryRejectsNonemptyOrInvalidJsonAndByteOverflowWithoutServiceMutation() throws Exception {
    allowRetry();
    for (String body :
        List.of(
            "[]",
            "[{}]",
            "{\"x\":null}",
            "null",
            "0",
            "\"\"",
            "{} {}",
            "{} true",
            "{} trailing",
            "{",
            "{ }\u0000",
            "\uFEFF{}",
            "{\u00a0}",
            "{" + " ".repeat(63) + "}",
            " ".repeat(65)))
      http.perform(
              post(EvidenceIngressSecurity.BASE + "/item/retry")
                  .header("Authorization", "Bearer " + token)
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    http.perform(
            post(EvidenceIngressSecurity.BASE + "/item/retry")
                .header("Authorization", "Bearer " + token)
                .content(new byte[] {(byte) 0xff}))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void dotOnlyNumbersCannotBeAcceptedButOtherDottedNumbersRemainRecoverable() throws Exception {
    for (String number : List.of(".", ".."))
      http.perform(
              post(EvidenceIngressSecurity.BASE)
                  .header("Authorization", "Bearer " + token)
                  .contentType("application/json")
                  .content(GenericEventInputTest.body("{\"n\":1}").replace("item-1", number)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    verifyNoInteractions(service);
    when(service.submit(eq(source), any()))
        .thenAnswer(
            call -> {
              GenericEventInput input = call.getArgument(1);
              return new GenericEventService.Result(
                  202, Map.of("id", "item", "sourceEventId", input.sourceEventId()));
            });
    for (String number : List.of(".item", "item.", "item..1", "...")) {
      when(service.byNumber(source, number))
          .thenReturn(Map.of("id", "item", "sourceEventId", number));
      http.perform(
              post(EvidenceIngressSecurity.BASE)
                  .header("Authorization", "Bearer " + token)
                  .contentType("application/json")
                  .content(GenericEventInputTest.body("{\"n\":1}").replace("item-1", number)))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.sourceEventId").value(number));
      http.perform(
              get(EvidenceIngressSecurity.BASE + "/by-source-event/" + number)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.sourceEventId").value(number));
      verify(service).byNumber(source, number);
    }
  }
}
