package com.bproject.trust.history;

import com.bproject.trust.identity.CurrentIdentity;
import com.bproject.trust.identity.PlatformPrincipal;
import com.bproject.trust.logevidence.LogOperations;
import com.bproject.trust.shared.web.ApiError;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Explicit domain queries; no client-controlled table names or sensitive certificate/key material.
 */
@RestController
@RequestMapping("/api/v1")
public class HistoryController {
  private final JdbcTemplate db;
  private final CurrentIdentity identity;
  private final LogOperations logs;

  public HistoryController(JdbcTemplate db, CurrentIdentity identity, LogOperations logs) {
    this.db = db;
    this.identity = identity;
    this.logs = logs;
  }

  private Map<String, Object> history(String fields, String from, int page, Object... scope) {
    return historyOrdered(fields, from, "v.retention_revision DESC", page, scope);
  }

  private Map<String, Object> historyOrdered(
      String fields, String from, String order, int page, Object... scope) {
    if (page < 0 || page > 100000) throw new ApiError(400, "历史页码无效");
    var args = new ArrayList<Object>(Arrays.asList(scope));
    args.add(51);
    args.add(page * 50);
    var rows =
        db.queryForList(
            "SELECT v.retention_revision AS version,v.change_actor AS actor,v.change_operation AS"
                + " operation,v.change_reason AS reason,v.changed_at AS happened_at,"
                + fields
                + " "
                + from
                + " ORDER BY "
                + order
                + " LIMIT ? OFFSET ?",
            args.toArray());
    boolean more = rows.size() > 50;
    if (more) rows = new ArrayList<>(rows.subList(0, 50));
    return Map.of("items", rows, "page", page, "hasMore", more);
  }

  private String walletScope(Authentication a) {
    if (!(a.getPrincipal() instanceof PlatformPrincipal))
      throw new ApiError(403, "钱包历史需要 IAM 实名登录");
    return identity.org(a);
  }

  @GetMapping("/wallets/{id}/history")
  @PreAuthorize("hasAuthority('trust:wallet:read')")
  public Object wallet(
      Authentication a, @PathVariable String id, @RequestParam(defaultValue = "0") int page) {
    return history(
        "b.label,b.fingerprint,v.state",
        "FROM wallets_versions v JOIN wallets b ON b.id=v.id WHERE b.id=? AND b.org_id=?",
        page,
        id,
        walletScope(a));
  }

  @GetMapping("/wallets/bindings/{source}/history")
  @PreAuthorize("hasAuthority('trust:wallet:read')")
  public Object binding(
      Authentication a, @PathVariable String source, @RequestParam(defaultValue = "0") int page) {
    return history(
        "v.source_system,v.wallet_id,v.revision AS binding_revision,v.change_request_id",
        "FROM wallet_bindings_versions v WHERE v.source_system=? AND v.org_id=?",
        page,
        source,
        walletScope(a));
  }

  @GetMapping("/wallets/requests/{id}/history")
  @PreAuthorize("hasAuthority('trust:wallet:read')")
  public Object request(
      Authentication a, @PathVariable String id, @RequestParam(defaultValue = "0") int page) {
    return history(
        "b.action,b.proposed_by,v.state,v.reviewed_by,v.reviewed_at",
        "FROM wallet_requests_versions v JOIN wallet_requests b ON b.id=v.id WHERE b.id=? AND"
            + " b.org_id=?",
        page,
        id,
        walletScope(a));
  }

  @GetMapping("/identity-management/{id}/history")
  @PreAuthorize("hasAuthority('trust:identity:read')")
  public Object subject(
      Authentication a, @PathVariable String id, @RequestParam(defaultValue = "0") int page) {
    String org = identity.org(a);
    return history(
        "b.user_id,v.org_id,v.revision AS"
            + " identity_revision,v.desired_state,v.action,v.status,v.certificate_version,v.last_error",
        "FROM fabric_identity_versions v JOIN fabric_identity b ON b.id=v.id JOIN"
            + " fabric_identity_read current_identity ON current_identity.id=v.id WHERE v.id=? AND"
            + " v.org_id=? AND current_identity.org_id=?",
        page,
        id,
        org,
        org);
  }

  @GetMapping("/identity-management/{id}/certificates/{version}/history")
  @PreAuthorize("hasAuthority('trust:identity:read')")
  public Object certificate(
      Authentication a,
      @PathVariable String id,
      @PathVariable int version,
      @RequestParam(defaultValue = "0") int page) {
    String org = identity.org(a);
    return history(
        "b.version AS"
            + " certificate_version,v.state,v.fingerprint,v.not_before,v.not_after,v.network_state,v.crl_state",
        "FROM fabric_certificate_versions v JOIN fabric_certificate b ON"
            + " b.identity_id=v.identity_id AND b.version=v.version JOIN fabric_identity_read i ON"
            + " i.id=v.identity_id WHERE v.identity_id=? AND v.version=? AND b.org_id=? AND"
            + " i.org_id=?",
        page,
        id,
        version,
        org,
        org);
  }

  @GetMapping("/identity-management/{id}/certificates/history")
  @PreAuthorize("hasAuthority('trust:identity:read')")
  public Object certificates(
      Authentication a, @PathVariable String id, @RequestParam(defaultValue = "0") int page) {
    String org = identity.org(a);
    return historyOrdered(
        "b.version AS"
            + " certificate_version,v.state,v.fingerprint,v.not_before,v.not_after,v.network_state,v.crl_state",
        "FROM fabric_certificate_versions v JOIN fabric_certificate b ON"
            + " b.identity_id=v.identity_id AND b.version=v.version JOIN fabric_identity_read i ON"
            + " i.id=v.identity_id WHERE v.identity_id=? AND b.org_id=? AND i.org_id=?",
        "v.version DESC,v.retention_revision DESC",
        page,
        id,
        org,
        org);
  }

  @GetMapping("/audit/settings/history")
  public Object settings(Authentication a, @RequestParam(defaultValue = "0") int page) {
    logs.requireSettingsHistory(a);
    return history(
        "v.archive_paused,v.chain_paused", "FROM audit_log_settings_versions v WHERE v.id=1", page);
  }

  @GetMapping("/events/{id}/processing-history")
  public Object event(
      Authentication a, @PathVariable String id, @RequestParam(defaultValue = "0") int page) {
    return history(
        "v.file_state,v.chain_state,v.manifest_cid,v.tx_id,v.block_number,v.last_error",
        "FROM events_versions v JOIN events b ON b.id=v.id WHERE b.id=? AND b.org_id=? AND"
            + " b.record_kind='LEGACY_V1'",
        page,
        id,
        identity.org(a));
  }
}
