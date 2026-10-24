package com.bdemo.iam.security;

import static org.junit.jupiter.api.Assertions.*;
import com.bdemo.iam.common.BizException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ReadonlySsoTicketTest {
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  private MockHttpServletRequest request(String method,String endpoint){
    LoginUser user=new LoginUser();user.setReadOnly(true);user.setRoles(Set.of("PLATFORM_AUDITOR_READONLY"));
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,java.util.List.of()));
    var request=new MockHttpServletRequest(method,"/api/v1/iam"+endpoint);request.setContextPath("/api/v1/iam");return request;
  }
  @Test void onlyTicketIssuanceIsSessionMaintenance(){
    assertTrue(new ReadonlyHardVetoInterceptor().preHandle(request("POST","/auth/sso/ticket"),new MockHttpServletResponse(),new Object()));
  }
  @Test void businessWritesRemainForbidden(){
    assertThrows(BizException.class,()->new ReadonlyHardVetoInterceptor().preHandle(request("POST","/users"),new MockHttpServletResponse(),new Object()));
  }
  @Test void matchingSuffixDoesNotCreateAnException(){
    for(String path:java.util.List.of("/users/auth/sso/ticket","/auth/sso/ticket/revoke","/auth/sso/verify-and-consume"))
      assertThrows(BizException.class,()->new ReadonlyHardVetoInterceptor().preHandle(request("POST",path),new MockHttpServletResponse(),new Object()));
  }
}
