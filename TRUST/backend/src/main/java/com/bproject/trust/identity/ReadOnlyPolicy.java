package com.bproject.trust.identity;

import org.springframework.security.core.Authentication;

/** Strict viewing: a restriction, never a grant of data or cross-organisation access. */
public final class ReadOnlyPolicy {
  private ReadOnlyPolicy() {}

  public static boolean restricted(Authentication authentication) {
    return authentication != null
        && authentication.getPrincipal() instanceof PlatformPrincipal p && p.readOnly();
  }

  public static boolean permits(String path, String method) {
    if (!path.startsWith("/api/v1/")) return true;
    if (path.endsWith("/download") || path.endsWith("/export")) return false;
    if (java.util.Set.of("GET", "HEAD", "OPTIONS").contains(method)) return true;
    return "POST".equals(method)
        && (path.equals("/api/v1/logout") || path.equals("/api/v1/audit/page-actions")
            || path.matches("/api/v1/audit/page-actions/[A-Za-z0-9-]+/result"));
  }
}
