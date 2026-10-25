package com.bdemo.iam.sso;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bdemo.iam.security.LoginUser;
import com.bdemo.iam.sso.mapper.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SsoGlobalLogoutTest {
  @Test void globalLogoutDeliversToEveryDistinctRevokedSession(){
    var tickets=mock(SsoTicketMapper.class);var records=mock(SsoLogoutMapper.class);var notifier=mock(SsoLogoutNotifier.class);
    var user=new LoginUser();user.setUserId("synthetic-user");
    when(tickets.findAudiencesBySession(anyString())).thenReturn(List.of("wms"));
    when(notifier.notifyLogout(anyString(),eq("synthetic-user"),eq(List.of("wms")),eq("PORTAL_LOGOUT"))).thenReturn(List.of("wms"));
    var result=new SsoLogoutCoordinator(tickets,records,notifier).logoutAll(user,"first",List.of("first","second","second"),new MockHttpServletRequest());
    assertEquals(true,result.toBody().get("downstreamComplete"));
    verify(notifier).notifyLogout("first","synthetic-user",List.of("wms"),"PORTAL_LOGOUT");
    verify(notifier).notifyLogout("second","synthetic-user",List.of("wms"),"PORTAL_LOGOUT");
    verifyNoMoreInteractions(notifier);
  }
  @Test void oneFailedSessionPreventsAnAudienceFromBeingReportedComplete(){
    var tickets=mock(SsoTicketMapper.class);var records=mock(SsoLogoutMapper.class);var notifier=mock(SsoLogoutNotifier.class);
    when(tickets.findAudiencesBySession(anyString())).thenReturn(List.of("wms"));
    when(notifier.notifyLogout(eq("first"),anyString(),anyList(),anyString())).thenReturn(List.of("wms"));
    when(notifier.notifyLogout(eq("second"),anyString(),anyList(),anyString())).thenReturn(List.of());
    var result=new SsoLogoutCoordinator(tickets,records,notifier).logoutAll(null,"first",List.of("first","second"),new MockHttpServletRequest());
    assertEquals(false,result.toBody().get("downstreamComplete"));assertTrue(result.deliveredAudiences().isEmpty());
  }
}
