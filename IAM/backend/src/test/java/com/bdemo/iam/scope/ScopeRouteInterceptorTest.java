package com.bdemo.iam.scope;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bdemo.iam.common.BizException;
import com.bdemo.iam.security.LoginUser;
import com.bproject.iam.context.ScopeSql;
import com.bproject.iam.context.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ScopeRouteInterceptorTest {
  @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
  private void actor(boolean manager){
    var context=mock(UserContext.class);
    when(context.mayManage()).thenReturn(manager);
    when(context.scopeMode()).thenReturn(ScopeSql.Mode.DENY);
    when(context.adminLevel()).thenReturn(manager?"L2_PROJECT":"L4_OPERATOR");
    var user=new LoginUser();user.setUserContext(context);
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user,null,java.util.List.of()));
  }
  private boolean request(String method,String path){
    var request=new MockHttpServletRequest(method,"/api/v1/iam"+path);
    request.setContextPath("/api/v1/iam");
    return new ScopeRouteInterceptor().preHandle(request,new MockHttpServletResponse(),new Object());
  }
  @Test void ungrantedManagerCanDiscoverAndAuthenticateToManagementOnly(){
    actor(true);
    assertTrue(request("GET","/navigation/tree"));
    assertTrue(request("POST","/auth/sso/ticket"));
    assertTrue(request("GET","/management/overview"));
    for(var path:java.util.List.of("/navigation/menus","/users","/roles"))
      assertThrows(BizException.class,()->request("GET",path));
    assertThrows(BizException.class,()->request("POST","/navigation/tree"));
    assertThrows(BizException.class,()->request("GET","/auth/sso/ticket"));
  }
  @Test void deniedBusinessUserGainsNoManagementDiscoveryOrTicketAccess(){
    actor(false);
    for(var path:java.util.List.of("/navigation/tree","/management/overview"))
      assertThrows(BizException.class,()->request("GET",path));
    assertThrows(BizException.class,()->request("POST","/auth/sso/ticket"));
    assertTrue(request("GET","/auth/me"));
    assertTrue(request("POST","/auth/logout"));
  }
}
