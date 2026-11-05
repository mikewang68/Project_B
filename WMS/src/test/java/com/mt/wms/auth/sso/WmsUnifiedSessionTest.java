package com.mt.wms.auth.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import jakarta.servlet.FilterChain;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WmsUnifiedSessionTest {
  @Test void v2ConsumerCannotBypassCentralAuthorizationUsingAnOldNativeSession() throws Exception {
    var service=mock(WmsSsoExchangeService.class);when(service.requiresUnifiedSession()).thenReturn(true);
    var filter=new WmsSsoSessionGuardFilter(new ObjectMapper()).withAuthorization(service);
    for(String path:new String[]{"/api/v1/stockin","/api/v1/auth/login"}) {
      var request=new MockHttpServletRequest("POST",path);var response=new MockHttpServletResponse();
      var chain=mock(FilterChain.class);
      if(path.endsWith("stockin"))request.getSession(true).setAttribute("old-native-login",true);
      filter.doFilter(request,response,chain);
      assertEquals(401,response.getStatus());verifyNoInteractions(chain);
    }
  }
}
