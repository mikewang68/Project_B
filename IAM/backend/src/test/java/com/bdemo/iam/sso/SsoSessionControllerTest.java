package com.bdemo.iam.sso;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.bdemo.iam.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SsoSessionControllerTest {
  @Test void expiredNativeSessionReturns401AndWrongServiceReturns403() throws Exception {
    var services=mock(SsoServiceIdentity.class);
    var authorization=mock(SsoAuthorizationService.class);
    when(services.authorize("wms","test-service")).thenReturn(true);
    when(authorization.current("subject","revoked-sid","wms"))
        .thenThrow(new SsoTicketService.SsoTicketException("inactive"));
    var mvc=MockMvcBuilders.standaloneSetup(new SsoSessionController(services,authorization))
        .setControllerAdvice(new GlobalExceptionHandler()).build();
    String body="{\"subject\":\"subject\",\"sessionId\":\"revoked-sid\",\"audience\":\"wms\"}";
    mvc.perform(post("/auth/sso/session/introspect").contentType("application/json")
        .header("X-SSO-Service-Token","test-service").content(body)).andExpect(status().isUnauthorized());
    mvc.perform(post("/auth/sso/session/introspect").contentType("application/json")
        .header("X-SSO-Service-Token","wrong-service").content(body)).andExpect(status().isForbidden());
    verify(authorization,times(1)).current("subject","revoked-sid","wms");
  }
}
