package com.mt.wms.auth.sso;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpSession;

class WmsSsoLifecycleTest {
  @BeforeEach void reset(){WmsSsoLogoutRegistry.resetForTests();}
  @Test void sessionRotationStillReceivesGlobalLogout(){
    var session=new MockHttpSession();String old=session.getId();
    WmsSsoLogoutRegistry.bind(old,"iam-sid","subject");session.changeSessionId();
    new WmsSsoSessionLifecycle().sessionIdChanged(new HttpSessionEvent(session),old);
    assertNull(WmsSsoLogoutRegistry.get(old));
    assertTrue(WmsSsoLogoutRegistry.revokeByIamSid("iam-sid").contains(session.getId()));
    assertTrue(WmsSsoLogoutRegistry.isSessionRevoked(session.getId()));
  }
  @Test void destroyedSessionIsRemovedFromBindingIndex(){
    var session=new MockHttpSession();WmsSsoLogoutRegistry.bind(session.getId(),"iam-sid","subject");
    new WmsSsoSessionLifecycle().sessionDestroyed(new HttpSessionEvent(session));
    assertNull(WmsSsoLogoutRegistry.get(session.getId()));assertTrue(WmsSsoLogoutRegistry.revokeByIamSid("iam-sid").isEmpty());
  }
  @Test void rebindingDoesNotLeaveAStaleReverseIndex(){
    WmsSsoLogoutRegistry.bind("local","old-iam-sid","subject");
    WmsSsoLogoutRegistry.bind("local","new-iam-sid","subject");
    assertTrue(WmsSsoLogoutRegistry.revokeByIamSid("old-iam-sid").isEmpty());
    assertFalse(WmsSsoLogoutRegistry.isSessionRevoked("local"));
    assertTrue(WmsSsoLogoutRegistry.revokeByIamSid("new-iam-sid").contains("local"));
  }
}
