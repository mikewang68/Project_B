package com.mt.wms.auth.sso;

import com.mt.wms.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.web.context.SecurityContextRepository;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WmsSsoHttpFailureTest {
    final WmsSsoExchangeService exchange=mock(WmsSsoExchangeService.class);
    final WmsSsoProperties properties=mock(WmsSsoProperties.class);
    final WmsSsoSourceGuard source=mock(WmsSsoSourceGuard.class);
    final WmsSsoController controller=new WmsSsoController(exchange,properties,source,mock(AuthService.class),mock(SecurityContextRepository.class));
    final MockHttpServletRequest request=new MockHttpServletRequest();
    final MockHttpServletResponse response=new MockHttpServletResponse();
    void configured(){when(properties.isUsable()).thenReturn(true);when(source.isAllowed(request)).thenReturn(true);}
    void noSession(){assertNull(request.getSession(false));assertNull(response.getHeader("Set-Cookie"));}
    @Test void disabledIs503WithoutSession(){controller.exchange(new WmsSsoController.ExchangeRequest("offline"),null,request,response);assertEquals(503,response.getStatus());noSession();}
    @Test void sourceDeniedIs403WithoutSession(){when(properties.isUsable()).thenReturn(true);controller.exchange(new WmsSsoController.ExchangeRequest("offline"),null,request,response);assertEquals(403,response.getStatus());noSession();verifyNoInteractions(exchange);}
    @Test void noMappingIs403WithoutSession(){configured();when(exchange.exchange("offline")).thenReturn(WmsSsoExchangeService.Result.fail("SSO_NO_LOCAL_MAPPING","denied"));controller.exchange(new WmsSsoController.ExchangeRequest("offline"),null,request,response);assertEquals(403,response.getStatus());noSession();}
    @Test void replayIs401WithoutSession(){configured();when(exchange.exchange("offline")).thenReturn(WmsSsoExchangeService.Result.fail("SSO_TICKET_REJECTED","denied"));controller.exchange(new WmsSsoController.ExchangeRequest("offline"),null,request,response);assertEquals(401,response.getStatus());noSession();}
    @Test void unauthorizedRevokeIs403WithoutSession(){controller.revoke(new WmsSsoController.RevokeRequest("offline-sid"),null,request,response);assertEquals(403,response.getStatus());noSession();}
}
