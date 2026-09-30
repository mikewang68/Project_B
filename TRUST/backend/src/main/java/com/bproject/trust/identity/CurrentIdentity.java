package com.bproject.trust.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class CurrentIdentity {
  private final JdbcTemplate db;

  public CurrentIdentity(JdbcTemplate db) {
    this.db = db;
  }

  public String org(Authentication authentication) {
    if (authentication.getPrincipal() instanceof PlatformPrincipal p) return p.orgId();
    return db.queryForObject(
        "SELECT org_id FROM trust_users WHERE username=?", String.class, authentication.getName());
  }

  public String actor(Authentication authentication) {
    if (authentication.getPrincipal() instanceof PlatformPrincipal p) return "iam:" + p.userId();
    return authentication.getName();
  }
}
