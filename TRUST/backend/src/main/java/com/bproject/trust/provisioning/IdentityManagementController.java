package com.bproject.trust.provisioning;

import com.bproject.trust.identity.CurrentIdentity;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Browser read model; lifecycle remains coordinated by IAM's durable user revision. */
@RestController
@RequestMapping("/api/v1/identity-management")
public class IdentityManagementController {
  private final JdbcTemplate db;
  private final CurrentIdentity identity;

  public IdentityManagementController(JdbcTemplate db, CurrentIdentity identity) {
    this.db = db;
    this.identity = identity;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('trust:identity:read')")
  public List<Map<String, Object>> list(Authentication actor) {
    return db.queryForList(
        "SELECT i.id,i.issuer,i.tenant,i.user_id,i.org_id,CASE WHEN i.status='READY' AND"
            + " (c.not_after IS NULL OR c.not_after<=CURRENT_TIMESTAMP) THEN 'EXPIRED' ELSE"
            + " i.status END AS"
            + " status,i.desired_state,i.revision,i.certificate_version,i.last_error,c.msp_id,c.enrollment_id,c.fingerprint,c.not_after,c.network_state,c.crl_state"
            + " FROM fabric_identity i LEFT JOIN fabric_certificate c ON c.identity_id=i.id AND"
            + " c.version=i.certificate_version WHERE i.org_id=? ORDER BY i.created_at DESC LIMIT"
            + " 200",
        identity.org(actor));
  }
}
