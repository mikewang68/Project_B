package com.bdemo.iam.sso;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

class IamNativeSessionsTest {
  @Test
  void revokesEveryNativeSessionForOneSidAndPreservesOtherSessions() {
    var registry = new IamNativeSessions();
    var first = session("sid-a"); var second = session("sid-a"); var other = session("sid-b");
    registry.register(first); registry.register(second); registry.register(other);
    assertEquals(2, registry.revoke("sid-a"));
    assertTrue(first.isInvalid()); assertTrue(second.isInvalid()); assertFalse(other.isInvalid());
    assertEquals(0, registry.revoke("sid-a"));
    assertEquals(1, registry.revoke("sid-b"));
  }

  @Test
  void expiryAndRepeatedNotificationsAreSafe() {
    var registry = new IamNativeSessions(); var expired = session("sid-a");
    registry.register(expired); registry.sessionDestroyed(new HttpSessionEvent(expired)); expired.invalidate();
    assertEquals(0, registry.revoke("sid-a"));
    assertThrows(ResponseStatusException.class, () -> registry.revoke(" "));
  }

  private MockHttpSession session(String sid) {
    var session = new MockHttpSession(); session.setAttribute("IAM_NATIVE_SID", sid); return session;
  }

  @Test
  void sessionFixationRotationStillAllowsRevocation() {
    var registry = new IamNativeSessions(); var session = session("sid-a"); registry.register(session);
    String oldId = session.getId(); session.changeSessionId();
    registry.sessionIdChanged(new HttpSessionEvent(session), oldId);
    assertEquals(1, registry.revoke("sid-a")); assertTrue(session.isInvalid());
  }
}
