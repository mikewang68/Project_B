package com.bproject.trust.ports;

import java.util.List;
import java.util.Set;

public interface PlatformIdentity {
  record User(String id, String username, List<String> orgCodes, Set<String> permissions,
              boolean readOnly) {
    public User(String id, String username, List<String> orgCodes, Set<String> permissions) {
      this(id, username, orgCodes, permissions, false);
    }
  }

  String login(String username, String password);

  User current(String token);
}
