package com.bproject.trust.provisioning;

import com.bproject.trust.ports.*;
import com.bproject.trust.shared.json.Json;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Leased durable work with revision fencing. CA/Gateway never run inside SQL transactions. */
@Component
public class IdentityWorker {
  private final com.bproject.trust.shared.BoundedWork executor =
      new com.bproject.trust.shared.BoundedWork("trust-identity", 1);
  private record Claim(String token, long revision) {}
  private final java.util.concurrent.ConcurrentHashMap<String, Claim> active =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final IdentityProviderSettings settings;
  private final IdentityAuthority ca;
  private final IdentityNetwork network;
  private final FabricIdentityService service;

  public IdentityWorker(
      JdbcTemplate db,
      TransactionTemplate tx,
      IdentityProviderSettings settings,
      IdentityAuthority ca,
      IdentityNetwork network,
      FabricIdentityService service) {
    this.db = db;
    this.tx = tx;
    this.settings = settings;
    this.ca = ca;
    this.network = network;
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${trust.identity.poll-ms:2000}")
  public void dispatch() { executor.batch(this::recover); }

  @jakarta.annotation.PreDestroy
  void close() { executor.close(); }

  @Scheduled(fixedDelay = 15000, scheduler = "leaseScheduler")
  public void renew() {
    active.forEach((id, claim) -> {
      try {
        db.update("UPDATE fabric_identity SET lease_until=CURRENT_TIMESTAMP+INTERVAL '120 seconds'"
            + " WHERE id=? AND revision=? AND lease_token=? AND lease_until>clock_timestamp()",
            id, claim.revision(), claim.token());
      } catch (Exception unavailable) { /* Revision/lease fencing still rejects stale work. */ }
    });
  }

  public void recover() {
    if (executor.closing() || !settings.configured()) return;
    for (var row : due()) process((String) row.get("id"));
  }

  List<Map<String, Object>> due() {
    return db.queryForList(
        "SELECT id FROM fabric_identity WHERE (status IN"
            + " ('PENDING','ISSUED','NETWORK_PENDING','RETRY','READY','REVOCATION_PENDING')"
            + " OR (status='DISABLED' AND EXISTS(SELECT 1 FROM fabric_certificate c WHERE"
            + " c.identity_id=fabric_identity.id AND c.crl_state='REVOKING'))) AND"
            + " next_attempt<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR"
            + " lease_until<CURRENT_TIMESTAMP) ORDER BY next_attempt LIMIT 8");
  }

  public void process(String id) {
    if (executor.closing()) return;
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) throw new IllegalStateException("Remote work in transaction");
    String lease = UUID.randomUUID().toString();
    var identity =
        tx.execute(
            s -> {
              if (db.update(
                      "UPDATE fabric_identity SET"
                          + " lease_token=?,lease_until=CURRENT_TIMESTAMP +"
                          + " INTERVAL '120 seconds' WHERE id=? AND"
                          + " (lease_until IS NULL OR"
                          + " lease_until<CURRENT_TIMESTAMP)",
                      lease,
                      id)
                  != 1) return null;
              return db.queryForMap("SELECT * FROM fabric_identity WHERE id=?", id);
            });
    if (identity == null) return;
    long revision = ((Number) identity.get("revision")).longValue();
    Claim claim = new Claim(lease, revision);
    active.put(id, claim);
    try {
      var cfg = settings.read();
      if (FabricIdentityService.requiresRevocation(
          (String) identity.get("action"), (String) identity.get("desired_state"))) {
        revokeAll(id, revision, lease, cfg);
        return;
      }
      if (db.queryForObject(
              "SELECT COUNT(*) FROM fabric_certificate WHERE identity_id=? AND"
                  + " crl_state='REVOKING'",
              Integer.class,
              id)
          > 0) {
        // A newer ACTIVE command remains accepted, but drains earlier irreversible intent
        // before selecting a usable certificate or issuing a new enrollment version.
        revokeAll(id, revision, lease, cfg);
        if (db.queryForObject(
                "SELECT COUNT(*) FROM fabric_certificate WHERE identity_id=? AND"
                    + " crl_state='REVOKING'",
                Integer.class,
                id)
            > 0) return;
      }
      if (!"ACTIVE".equals(identity.get("desired_state"))) return;
      var cert =
          tx.execute(
              s -> {
                var current =
                    db.queryForMap("SELECT * FROM fabric_identity WHERE id=? FOR" + " UPDATE", id);
                if (((Number) current.get("revision")).longValue() != revision
                    || !lease.equals(current.get("lease_token"))
                    || !current(id, revision, lease)) return null;
                var candidates =
                    db.queryForList(
                        "SELECT * FROM fabric_certificate WHERE"
                            + " identity_id=? ORDER BY version DESC",
                        id);
                if (!candidates.isEmpty()) {
                  var last = candidates.get(0);
                  boolean expiring =
                      last.get("not_after") instanceof java.util.Date expiry
                          && expiry
                              .toInstant()
                              .isBefore(
                                  java.time.Instant.now()
                                      .plus(7, java.time.temporal.ChronoUnit.DAYS));
                  if (!expiring
                      && identity.get("org_id").equals(last.get("org_id"))
                      && Set.of("PREPARING", "ISSUED").contains(last.get("state"))
                      && !"REVOKING".equals(last.get("crl_state"))
                      && (!"ROTATE".equals(identity.get("action"))
                          || ((Number) last.get("requested_revision")).longValue() == revision))
                    return last;
                }
                int version =
                    candidates.isEmpty()
                        ? 1
                        : ((Number) candidates.get(0).get("version")).intValue() + 1;
                var org = cfg.organizations().get((String) identity.get("org_id"));
                String enrollment = "iam_" + id.replace("-", "") + "_v" + version;
                db.update(
                    "INSERT INTO"
                        + " fabric_certificate(identity_id,version,requested_revision,org_id,msp_id,ca_id,enrollment_id,key_ref,state)"
                        + " VALUES(?,?,?,?,?,?,?,?, 'PREPARING')",
                    id,
                    version,
                    revision,
                    identity.get("org_id"),
                    org.mspId(),
                    org.caId(),
                    enrollment,
                    enrollment);
                service.audit(
                    id,
                    revision,
                    "SYSTEM",
                    "CERTIFICATE_ALLOCATED",
                    Json.write(Map.of("version", version)));
                return db.queryForMap(
                    "SELECT * FROM fabric_certificate WHERE identity_id=? AND" + " version=?",
                    id,
                    version);
              });
      if (cert == null) return;
      int version = ((Number) cert.get("version")).intValue();
      var org = cfg.organizations().get((String) cert.get("org_id"));
      if (!org.mspId().equals(cert.get("msp_id")) || !org.caId().equals(cert.get("ca_id")))
        throw new IllegalStateException("CA_MAPPING_CHANGED");
      var attributes =
          Map.of(
              "app.org",
              (String) cert.get("org_id"),
              "app.userId",
              (String) identity.get("user_id"),
              "app.issuer",
              (String) identity.get("issuer"),
              "app.tenant",
              (String) identity.get("tenant"));
      if (!current(id, revision, lease)) return;
      var material =
          ca.issue(
              org, (String) cert.get("enrollment_id"), (String) cert.get("key_ref"), attributes);
      tx.executeWithoutResult(
          s -> {
            db.queryForMap("SELECT id FROM fabric_identity WHERE id=? FOR UPDATE", id);
            if (!current(id, revision, lease)) return;
            db.update(
                "UPDATE fabric_certificate SET"
                    + " state='ISSUED',fingerprint=?,certificate_pem=?,not_before=?,not_after=?"
                    + " WHERE identity_id=? AND version=? AND state IN"
                    + " ('PREPARING','ISSUED')",
                material.fingerprint(),
                material.pem(),
                new java.sql.Timestamp(material.certificate().getNotBefore().getTime()),
                new java.sql.Timestamp(material.certificate().getNotAfter().getTime()),
                id,
                version);
            service.audit(
                id,
                revision,
                "SYSTEM",
                "CA_ISSUED",
                Json.write(Map.of("version", version, "fingerprint", material.fingerprint())));
          });
      if (!current(id, revision, lease)) return;
      var receipt =
          network.verify(
              org.mspId(), (String) cert.get("org_id"), (String) identity.get("user_id"), material);
      tx.executeWithoutResult(
          s -> {
            var locked =
                db.queryForMap(
                    "SELECT revision,lease_token,desired_state FROM"
                        + " fabric_identity WHERE id=? FOR UPDATE",
                    id);
            if (((Number) locked.get("revision")).longValue() != revision
                || !lease.equals(locked.get("lease_token"))
                || !"ACTIVE".equals(locked.get("desired_state"))) return;
            if (!current(id, revision, lease)
                || db.queryForObject(
                        "SELECT COUNT(*) FROM fabric_certificate WHERE"
                            + " identity_id=? AND crl_state='REVOKING'",
                        Integer.class,
                        id)
                    > 0) return;
            db.update(
                "UPDATE fabric_certificate SET state='RETIRED' WHERE identity_id=?"
                    + " AND version<>? AND state='ISSUED'",
                id,
                version);
            db.update(
                "UPDATE fabric_certificate SET"
                    + " network_state='VERIFIED',network_receipt=? WHERE"
                    + " identity_id=? AND version=?",
                Json.write(receipt),
                id,
                version);
            db.update(
                "UPDATE fabric_identity SET"
                    + " certificate_version=?,status='READY',last_error=NULL,next_attempt=CURRENT_TIMESTAMP"
                    + " + INTERVAL '1 hour',updated_at=CURRENT_TIMESTAMP WHERE id=?",
                version,
                id);
            service.audit(id, revision, "SYSTEM", "NETWORK_VERIFIED", Json.write(receipt));
          });
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      // A failed network probe cannot be reported as ready. Preserve durable issued cert for
      // retry.
      db.update(
          "UPDATE fabric_identity SET status=CASE WHEN action='REVOKE' THEN 'REVOCATION_PENDING'"
              + " ELSE 'RETRY'"
              + " END,last_error='IDENTITY_COMPONENT_UNAVAILABLE',attempts=attempts+1,next_attempt=CURRENT_TIMESTAMP"
              + " + INTERVAL '30 seconds' WHERE id=? AND revision=? AND lease_token=? AND"
              + " lease_until>clock_timestamp()",
          id,
          revision,
          lease);
    } finally {
      active.remove(id, claim);
      db.update(
          "UPDATE fabric_identity SET lease_token=NULL,lease_until=NULL WHERE id=? AND"
              + " lease_token=?",
          id,
          lease);
    }
  }

  private boolean current(String id, long revision, String lease) {
    if (executor.closing()) return false;
    return db.queryForObject(
            "SELECT COUNT(*) FROM fabric_identity WHERE id=? AND revision=? AND"
                + " lease_token=? AND desired_state='ACTIVE' AND"
                + " lease_until>clock_timestamp()",
            Integer.class,
            id,
            revision,
            lease)
        == 1;
  }

  private void revokeAll(
      String id, long revision, String lease, IdentityProviderSettings.Settings cfg)
      throws Exception {
    // Persist intent under the same identity lock used by lifecycle commands BEFORE any CA
    // call.
    // A newer revision inherits marked versions; it never reuses a certificate being revoked.
    var intent =
        tx.execute(
            s -> {
              var identity = revocationOwner(id, revision, lease);
              if (identity == null) return null;
              boolean all =
                  FabricIdentityService.requiresRevocation(
                      (String) identity.get("action"), (String) identity.get("desired_state"));
              var certificates =
                  db.queryForList(
                      "SELECT * FROM fabric_certificate WHERE identity_id=?"
                          + " AND state<>'CA_REVOKED'"
                          + (all ? "" : " AND crl_state='REVOKING'")
                          + " ORDER BY version",
                      id);
              int marked =
                  all
                      ? db.update(
                          "UPDATE fabric_certificate SET"
                              + " crl_state='REVOKING' WHERE"
                              + " identity_id=? AND state<>'CA_REVOKED'"
                              + " AND crl_state<>'REVOKING'",
                          id)
                      : 0;
              if (marked > 0)
                service.audit(
                    id,
                    revision,
                    "SYSTEM",
                    "REVOCATION_STARTED",
                    Json.write(
                        Map.of(
                            "targets",
                            certificates.stream()
                                .map(
                                    c ->
                                        Map.of(
                                            "version",
                                            c.get("version"),
                                            "enrollmentId",
                                            c.get("enrollment_id"),
                                            "orgId",
                                            c.get("org_id"),
                                            "caId",
                                            c.get("ca_id")))
                                .toList())));
              return new RevocationIntent(identity, certificates);
            });
    if (intent == null) return;
    var identity = intent.identity();
    for (var cert : intent.certificates()) {
      var org = cfg.organizations().get((String) cert.get("org_id"));
      if (org == null || !org.caId().equals(cert.get("ca_id")))
        throw new IllegalStateException("CA_MAPPING_CHANGED");
      if (!revocationCurrent(id, revision, lease)) return;
      // Resolve an ambiguous issuance before revoking it; this also closes a
      // deletion/issuance
      // race.
      if ("PREPARING".equals(cert.get("state"))) {
        var material =
            ca.issue(
                org,
                (String) cert.get("enrollment_id"),
                (String) cert.get("key_ref"),
                Map.of(
                    "app.org",
                    (String) cert.get("org_id"),
                    "app.userId",
                    (String) identity.get("user_id"),
                    "app.issuer",
                    (String) identity.get("issuer"),
                    "app.tenant",
                    (String) identity.get("tenant")));
        Boolean recorded =
            tx.execute(
                s -> {
                  if (revocationOwner(id, revision, lease) == null) return false;
                  db.update(
                      "UPDATE fabric_certificate SET"
                          + " state='ISSUED',fingerprint=?,certificate_pem=?,not_before=?,not_after=?"
                          + " WHERE identity_id=? AND version=? AND state='PREPARING'",
                      material.fingerprint(),
                      material.pem(),
                      new java.sql.Timestamp(material.certificate().getNotBefore().getTime()),
                      new java.sql.Timestamp(material.certificate().getNotAfter().getTime()),
                      id,
                      cert.get("version"));
                  return true;
                });
        if (!Boolean.TRUE.equals(recorded)) return;
      }
      if (!revocationCurrent(id, revision, lease)) return;
      ca.revoke(org, (String) cert.get("enrollment_id"));
      tx.executeWithoutResult(
          s -> {
            if (revocationOwner(id, revision, lease) == null) return;
            db.update(
                "UPDATE fabric_certificate SET"
                    + " state='CA_REVOKED',crl_state='PENDING',network_state='REVOCATION_UNVERIFIED'"
                    + " WHERE identity_id=? AND version=?",
                id,
                cert.get("version"));
            service.audit(
                id,
                revision,
                "SYSTEM",
                "CA_REVOKED",
                Json.write(Map.of("version", cert.get("version"))));
          });
    }
    tx.executeWithoutResult(
        s -> {
          var owner = revocationOwner(id, revision, lease);
          if (owner == null
              || !FabricIdentityService.requiresRevocation(
                  (String) owner.get("action"), (String) owner.get("desired_state"))) return;
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM fabric_certificate WHERE identity_id=?"
                      + " AND state<>'CA_REVOKED'",
                  Integer.class,
                  id)
              != 0) return;
          db.update(
              "UPDATE fabric_identity SET status='CRL_PENDING',last_error=NULL WHERE" + " id=?",
              id);
        });
  }

  private record RevocationIntent(
      Map<String, Object> identity, List<Map<String, Object>> certificates) {}

  private boolean revocationCurrent(String id, long revision, String lease) {
    if (executor.closing()) return false;
    return db.queryForObject(
            "SELECT COUNT(*) FROM fabric_identity WHERE id=? AND revision=? AND"
                + " lease_token=? AND lease_until>clock_timestamp()",
            Integer.class,
            id,
            revision,
            lease)
        == 1;
  }

  /** Must run inside a transaction; the returned lock also fences certificate writes. */
  private Map<String, Object> revocationOwner(String id, long revision, String lease) {
    var rows = db.queryForList("SELECT * FROM fabric_identity WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty() || !revocationCurrent(id, revision, lease)) return null;
    return rows.get(0);
  }
}
