package com.bdemo.iam.sso;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IamNativeSsoControllerTest {
  @Test
  void acceptsCoordinatorPayloadOnlyWithMatchingServiceIdentity() throws Exception {
    var services = mock(SsoServiceIdentity.class); var sessions = new IamNativeSessions();
    var nativeSession = new MockHttpSession(); nativeSession.setAttribute("IAM_NATIVE_SID", "sid-a");
    sessions.register(nativeSession);
    var controller = new IamNativeSsoController(services, mock(SsoTicketService.class),
        mock(SsoAuthorizationService.class), sessions);
    var json = new com.fasterxml.jackson.databind.ObjectMapper()
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    var mvc = MockMvcBuilders.standaloneSetup(controller)
        .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json)).build();
    String payload = "{\"iamSessionId\":\"sid-a\",\"audience\":\"iam\",\"subject\":\"subject-a\",\"reason\":\"PORTAL_LOGOUT\"}";
    mvc.perform(post("/auth/sso/native/revoke").contentType(MediaType.APPLICATION_JSON).content(payload))
        .andExpect(status().isForbidden());
    assertFalse(nativeSession.isInvalid());
    when(services.authorize("iam", "service-credential")).thenReturn(true);
    mvc.perform(post("/auth/sso/native/revoke").header("X-SSO-Service-Token", "service-credential")
        .contentType(MediaType.APPLICATION_JSON).content(payload))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.revoked").value(1));
    assertTrue(nativeSession.isInvalid());
  }
}
