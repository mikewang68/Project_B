package com.bproject.trust.provisioning;

import com.bproject.trust.adapters.fabric.GatewayIdentityNetwork;
import com.bproject.trust.config.IdentityInstance;
import com.bproject.trust.identity.IntegrationSettings;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.wallet.WalletKeyStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit operator CLI, never a web bean. Fetches committed peer configs and verifies CRL
 * enforcement.
 */
public final class CrlVerification {
  public record Peer(String endpoint, String serverName, Map<String, String> environment) {}

  public record Plan(
      String operator,
      String peerBinary,
      String peerSha256,
      String decoderBinary,
      String decoderSha256,
      String contract,
      String controlKeyRef,
      String controlMsp,
      String controlOrg,
      String controlUser,
      List<Peer> peers) {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2 || !args[0].matches("[a-f0-9-]{36}"))
      throw new IllegalArgumentException("identityId private-plan.json required");
    var instance =
        IdentityInstance.read(
            System.getenv("TRUST_INSTANCE_ALLOWLIST"), System.getenv("TRUST_INSTANCE"));
    if (!instance.databaseUrl().equals(System.getenv("TRUST_DB_URL")))
      throw new IllegalArgumentException("Wrong database");
    var plan = Json.MAPPER.readValue(Files.readString(Path.of(args[1])), Plan.class);
    if (!IdentityProviderSettings.id(plan.operator())
        || plan.peers() == null
        || plan.peers().size() < 2
        || plan.peers().stream().map(Peer::endpoint).distinct().count() != plan.peers().size())
      throw new IllegalArgumentException("Two distinct target peers required");
    checkBinary(plan.peerBinary(), plan.peerSha256());
    checkBinary(plan.decoderBinary(), plan.decoderSha256());
    var ds =
        new DriverManagerDataSource(
            instance.databaseUrl(), instance.appRole(), System.getenv("TRUST_DB_PASSWORD"));
    var props = new Properties();
    props.setProperty("currentSchema", "trust_data");
    ds.setConnectionProperties(props);
    var db = new JdbcTemplate(ds);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    String id = args[0];
    var original = db.queryForMap("SELECT * FROM fabric_identity WHERE id=?", id);
    if (!"CRL_PENDING".equals(original.get("status")))
      throw new IllegalStateException("Identity is not awaiting CRL verification");
    long revision = ((Number) original.get("revision")).longValue();
    var certs =
        db.queryForList(
            "SELECT * FROM fabric_certificate WHERE identity_id=? ORDER BY version", id);
    var settings =
        new IdentityProviderSettings(instance.root(), new IntegrationSettings(instance.root()))
            .read();
    var keys = new WalletKeyStore(instance.root());
    var control = keys.load(plan.controlKeyRef());
    var receipts = new ArrayList<Map<String, Object>>();
    Path evidence = Path.of(instance.root(), "runtime/crl-evidence", UUID.randomUUID().toString());
    Files.createDirectories(evidence);
    Files.setPosixFilePermissions(
        evidence, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    try {
      int index = 0;
      for (var peer : plan.peers()) {
        if (!peer.endpoint().equals(peer.environment().get("CORE_PEER_ADDRESS"))
            || peer.environment().keySet().stream()
                .anyMatch(
                    k ->
                        !Set.of(
                                "CORE_PEER_ADDRESS",
                                "CORE_PEER_LOCALMSPID",
                                "CORE_PEER_MSPCONFIGPATH",
                                "CORE_PEER_TLS_ENABLED",
                                "CORE_PEER_TLS_ROOTCERT_FILE",
                                "CORE_PEER_TLS_SERVERHOSTOVERRIDE")
                            .contains(k))
            || !"true".equals(peer.environment().get("CORE_PEER_TLS_ENABLED")))
          throw new IllegalArgumentException("Invalid peer environment");
        Path block = evidence.resolve("peer-" + index + ".pb"),
            decoded = evidence.resolve("peer-" + index + ".json");
        index++;
        run(
            List.of(
                plan.peerBinary(),
                "channel",
                "fetch",
                "config",
                block.toString(),
                "-c",
                instance.channel()),
            peer.environment());
        run(
            List.of(
                plan.decoderBinary(),
                "proto_decode",
                "--input",
                block.toString(),
                "--type",
                "common.Block",
                "--output",
                decoded.toString()),
            Map.of());
        if (Files.size(decoded) > 16777216) throw new IllegalArgumentException("Oversized config");
        var config = Json.MAPPER.readTree(Files.readString(decoded));
        var gateway =
            new GatewayIdentityNetwork(
                instance.root(),
                peer.endpoint(),
                peer.serverName(),
                instance.channel(),
                plan.contract());
        gateway.verify(plan.controlMsp(), plan.controlOrg(), plan.controlUser(), control);
        for (var row : certs) {
          if (!"CA_REVOKED".equals(row.get("state")))
            throw new IllegalStateException("CA revocation incomplete");
          var cert =
              (X509Certificate)
                  CertificateFactory.getInstance("X.509")
                      .generateCertificate(
                          new java.io.ByteArrayInputStream(
                              ((String) row.get("certificate_pem"))
                                  .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
          var org = settings.organizations().get((String) row.get("org_id"));
          if (org == null
              || !org.mspId().equals(row.get("msp_id"))
              || !org.caId().equals(row.get("ca_id")))
            throw new IllegalStateException("CA mapping changed");
          verifyConfig(
              config, instance.channel(), org.mspId(), cert, Path.of(org.enrollmentRootFile()));
          if (cert.getNotAfter().after(new Date())) {
            boolean denied = false;
            try {
              gateway.verify(
                  org.mspId(),
                  (String) row.get("org_id"),
                  (String) original.get("user_id"),
                  keys.load((String) row.get("key_ref")));
            } catch (Exception e) {
              denied = authenticationRejected(e, instance.channel(), org.mspId());
              if (!denied)
                throw new IllegalStateException("Ambiguous rejection; CRL remains pending", e);
            }
            if (!denied)
              throw new IllegalStateException(
                  "Revoked identity still accepted; CRL remains pending");
          }
        }
        receipts.add(
            Map.of(
                "peer",
                peer.endpoint(),
                "blockSha256",
                Json.sha(Files.readAllBytes(block)),
                "configSha256",
                Json.sha(Files.readAllBytes(decoded)),
                "control",
                "ACCEPTED",
                "revoked",
                "CRL_PRESENT_AND_DENIED_OR_EXPIRED"));
      }
      var receipt =
          Map.of(
              "identityId",
              id,
              "revision",
              revision,
              "channel",
              instance.channel(),
              "operator",
              plan.operator(),
              "checkedAt",
              java.time.Instant.now().toString(),
              "peers",
              receipts,
              "versions",
              certs.stream().map(c -> c.get("version")).toList());
      tx.executeWithoutResult(
          s -> {
            var locked =
                db.queryForMap(
                    "SELECT revision,status FROM fabric_identity WHERE id=? FOR UPDATE", id);
            if (((Number) locked.get("revision")).longValue() != revision
                || !"CRL_PENDING".equals(locked.get("status")))
              throw new IllegalStateException("Identity changed during verification");
            db.update(
                "UPDATE fabric_certificate SET crl_state='VERIFIED',network_state='REVOKED' WHERE"
                    + " identity_id=?",
                id);
            db.update(
                "UPDATE fabric_identity SET status='REVOKED',updated_at=CURRENT_TIMESTAMP WHERE"
                    + " id=?",
                id);
            new FabricIdentityService(db, tx)
                .audit(id, revision, plan.operator(), "CHANNEL_CRL_VERIFIED", Json.write(receipt));
          });
      Files.writeString(evidence.resolve("receipt.json"), Json.write(receipt));
      System.out.println(
          "CRL verified on all planned peers; audited receipt stored in private runtime");
    } catch (Exception e) {
      new FabricIdentityService(db, tx)
          .audit(id, revision, plan.operator(), "CHANNEL_CRL_CHECK_FAILED", "{}");
      throw e;
    }
  }

  static boolean authenticationRejected(Throwable error, String channel, String msp) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      var status = io.grpc.Status.fromThrowable(cause);
      if (status.getCode() == io.grpc.Status.Code.PERMISSION_DENIED
          || status.getCode() == io.grpc.Status.Code.UNAUTHENTICATED) return true;
      // Fabric 3.1 Gateway wraps the endorser's proposal authentication rejection.
      // A generic FAILED_PRECONDITION (contract, endorsement, availability, etc.)
      // must never count as proof of revocation.
      String expected = "evaluate call to endorser returned error: error validating proposal:"
          + " access denied: channel [" + channel + "] creator org [" + msp + "]";
      if (status.getCode() == io.grpc.Status.Code.FAILED_PRECONDITION
          && expected.equals(status.getDescription())) return true;
    }
    return false;
  }

  static void verifyConfig(
      JsonNode block, String channel, String msp, X509Certificate cert, Path caFile)
      throws Exception {
    var payload = block.path("data").path("data").path(0).path("payload");
    if (!channel.equals(payload.path("header").path("channel_header").path("channel_id").asText()))
      throw new IllegalArgumentException("Wrong channel");
    var groups =
        payload
            .path("data")
            .path("config")
            .path("channel_group")
            .path("groups")
            .path("Application")
            .path("groups");
    var factory = CertificateFactory.getInstance("X.509");
    Collection<? extends java.security.cert.Certificate> cas;
    try (var in = Files.newInputStream(caFile)) {
      cas = factory.generateCertificates(in);
    }
    for (var group : groups) {
      var config = group.path("values").path("MSP").path("value").path("config");
      if (!msp.equals(config.path("name").asText())) continue;
      for (var encoded : config.path("revocation_list")) {
        var crl =
            (X509CRL)
                factory.generateCRL(
                    new java.io.ByteArrayInputStream(Base64.getDecoder().decode(encoded.asText())));
        if (crl.getNextUpdate() == null
            || crl.getNextUpdate().before(new Date())
            || crl.getThisUpdate().after(new Date())
            || !crl.getIssuerX500Principal().equals(cert.getIssuerX500Principal())) continue;
        for (var candidate : cas) {
          var ca = (X509Certificate) candidate;
          if (!ca.getSubjectX500Principal().equals(crl.getIssuerX500Principal())) continue;
          try {
            cert.verify(ca.getPublicKey());
            crl.verify(ca.getPublicKey());
          } catch (java.security.GeneralSecurityException e) {
            continue;
          }
          if (crl.isRevoked(cert)) return;
        }
      }
    }
    throw new IllegalStateException("Target serial absent from a valid signed CRL in target MSP");
  }

  private static void checkBinary(String file, String sha) throws Exception {
    if (!Path.of(file).isAbsolute() || !Json.sha(Files.readAllBytes(Path.of(file))).equals(sha))
      throw new IllegalArgumentException("Operator binary digest mismatch");
  }

  private static void run(List<String> command, Map<String, String> env) throws Exception {
    var builder =
        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().keySet().removeIf(k -> k.startsWith("CORE_PEER_"));
    builder.environment().putAll(env);
    var p = builder.start();
    try {
      if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0)
        throw new IllegalStateException("Official read-only command failed");
    } finally {
      if (p.isAlive()) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
      }
    }
  }
}
