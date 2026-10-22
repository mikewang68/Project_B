package com.bproject.trust.identity;

import java.io.Serializable;

public record PlatformPrincipal(String userId, String username, String orgId, boolean readOnly)
    implements Serializable, java.security.Principal {
  public PlatformPrincipal(String userId, String username, String orgId) {
    this(userId, username, orgId, false);
  }
  public String getName() {
    return "iam:" + userId;
  }
}
