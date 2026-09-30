package com.bproject.trust.identity;

import java.io.Serializable;

public record PlatformPrincipal(String userId, String username, String orgId)
    implements Serializable, java.security.Principal {
  public String getName() {
    return "iam:" + userId;
  }
}
