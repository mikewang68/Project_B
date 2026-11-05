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
  void nativeExchangePreservesLegacyAliasAndAcceptsOnlyTheSameV2Principal() {
    assertExchange("alice",false,"subject-a","IAM",true);
    assertExchange("subject-a",true,"subject-a","IAM",true);
    assertExchange("subject-b",true,"subject-a","IAM",false);
    assertExchange("subject-a",false,"subject-a","IAM",false);
    assertExchange("subject-a",true,"subject-b","IAM",false);
    assertExchange("subject-a",true,"subject-a","WMS",false);
  }
  private void assertExchange(String local,boolean v2,String contextSubject,String audience,boolean allowed) {
    var services=mock(SsoServiceIdentity.class);var tickets=mock(SsoTicketService.class);
    var authorization=mock(SsoAuthorizationService.class);
    when(services.authorize("iam","credential")).thenReturn(true);
    var claims=mock(io.jsonwebtoken.Claims.class);
    when(claims.getSubject()).thenReturn("subject-a");
    when(claims.getId()).thenReturn("ticket-a");
    when(claims.get("sid",String.class)).thenReturn("session-a");
    when(tickets.verify("ticket","iam")).thenReturn(claims);
    when(tickets.consume("ticket-a","iam")).thenReturn(true);
    var data=new java.util.HashMap<String,Object>();
    data.put("username","alice");data.put("localSubject",local);
    if(v2) {
      var context=mock(com.bproject.iam.context.UserContext.class);
      when(context.userId()).thenReturn(contextSubject);
      when(context.serviceAudience()).thenReturn(audience);
      data.put("userContext",context);
    }
    when(authorization.current("subject-a","session-a","iam")).thenReturn(data);
    var controller=new IamNativeSsoController(services,tickets,authorization,new IamNativeSessions());
    var request=new org.springframework.mock.web.MockHttpServletRequest();
    if(allowed) {
      controller.exchange(new IamNativeSsoController.Exchange("ticket"),"credential",request);
      assertEquals("subject-a",request.getSession(false).getAttribute("IAM_NATIVE_SUBJECT"));
      assertNotNull(request.getSession(false).getAttribute("IAM_NATIVE_CSRF"));
      verify(tickets).consume("ticket-a","iam");
    }else{
      var failure=assertThrows(org.springframework.web.server.ResponseStatusException.class,
          ()->controller.exchange(new IamNativeSsoController.Exchange("ticket"),"credential",request));
      assertEquals(403,failure.getStatusCode().value());
      assertNull(request.getSession(false));
      verify(tickets,never()).consume(anyString(),anyString());
    }
  }
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
