package com.bdemo.iam.sso;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import jakarta.servlet.http.HttpSessionIdListener;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Native browser sessions are also removed by back-channel logout and session expiry. */
@Component
public class IamNativeSessions implements HttpSessionListener, HttpSessionIdListener {
  private final ConcurrentHashMap<String, HttpSession> sessions = new ConcurrentHashMap<>();

  public void register(HttpSession session) {
    if (sessions.size() >= 10000) {
      session.invalidate();
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "会话容量已满");
    }
    sessions.put(session.getId(), session);
  }

  public int revoke(String sid) {
    if (sid == null || sid.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    int count = 0;
    for (var entry : sessions.entrySet()) {
      try {
        if (sid.equals(entry.getValue().getAttribute("IAM_NATIVE_SID"))) {
          entry.getValue().invalidate();
          sessions.remove(entry.getKey(), entry.getValue());
          count++;
        }
      } catch (IllegalStateException expired) {
        sessions.remove(entry.getKey(), entry.getValue());
      }
    }
    return count;
  }

  @Override
  public void sessionDestroyed(HttpSessionEvent event) {
    sessions.entrySet().removeIf(entry -> entry.getValue() == event.getSession());
  }

  @Override
  public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
    if (sessions.remove(oldSessionId, event.getSession())) {
      sessions.put(event.getSession().getId(), event.getSession());
    }
  }
}
