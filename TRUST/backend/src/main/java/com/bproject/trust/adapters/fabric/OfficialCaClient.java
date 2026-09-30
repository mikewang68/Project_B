package com.bproject.trust.adapters.fabric;

import com.bproject.trust.ports.IdentityAuthority;
import com.bproject.trust.provisioning.IdentityProviderSettings;
import com.bproject.trust.provisioning.IdentityProviderSettings.Organization;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.wallet.WalletKeyStore;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Signers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Official maintained CA CLI. Durable BCCSP key directory permits recovery of lost enroll
 * responses.
 */
@Component
public class OfficialCaClient implements IdentityAuthority {
  private final IdentityProviderSettings settings;
  private final WalletKeyStore keys;
  private final Path jobs;

  public OfficialCaClient(
      IdentityProviderSettings settings, WalletKeyStore keys, @Value("${trust.root}") String root) {
    this.settings = settings;
    this.keys = keys;
    this.jobs = Path.of(root, "runtime/secrets/ca-jobs");
  }

  @Override
  public WalletKeyStore.Material issue(
      Organization org, String enrollmentId, String keyRef, Map<String, String> attributes)
      throws Exception {
    if (!enrollmentId.matches("[a-zA-Z0-9_-]{1,80}") || !keyRef.matches("[a-zA-Z0-9_-]{1,100}"))
      throw new IllegalArgumentException();
    secureDirectory(jobs);
    Path job = jobs.resolve(keyRef);
    secureDirectory(job);
    try (var channel =
            java.nio.channels.FileChannel.open(
                job.resolve("job.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var lock = channel.tryLock()) {
      if (lock == null) throw new IllegalStateException("CA_JOB_BUSY");
      if (Files.exists(job.resolve("revoked")))
        throw new IllegalStateException("CA_IDENTITY_REVOKED");
      if (keys.contains(keyRef)) {
        var material = checked(keys.load(keyRef), org, enrollmentId, attributes);
        cleanupPlaintext(job);
        return material;
      }
      Path secret = job.resolve("enrollment.secret");
      if (!Files.exists(secret)) {
        byte[] random = new byte[32];
        new java.security.SecureRandom().nextBytes(random);
        Files.writeString(secret, HexFormat.of().formatHex(random), StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"));
        try (var ch = java.nio.channels.FileChannel.open(secret, StandardOpenOption.WRITE)) {
          ch.force(true);
        }
      }
      String password = Files.readString(secret);
      Map<String, String> attrs = new TreeMap<>(org.attributes());
      attrs.putAll(attributes);
      String registrationAttrs =
          attrs.entrySet().stream()
              .map(e -> e.getKey() + "=" + e.getValue() + ":ecert")
              .collect(java.util.stream.Collectors.joining(","));
      // Same unique registration name and same persisted secret on every attempt. Never modify
      // identities.
      run(
          org,
          Path.of(org.registrarHome()),
          Map.of("FABRIC_CA_CLIENT_ID_SECRET", password),
          List.of(
              "register",
              "--id.name",
              enrollmentId,
              "--id.type",
              "client",
              "--id.affiliation",
              org.affiliation(),
              "--id.maxenrollments",
              "1",
              "--id.attrs",
              registrationAttrs),
          true);
      Path candidates = job.resolve("certificates");
      secureDirectory(candidates);
      // This also resolves CA issuance success followed by response loss/process death/DB rollback.
      run(
          org,
          Path.of(org.registrarHome()),
          Map.of(),
          List.of(
              "certificate",
              "list",
              "--id",
              enrollmentId,
              "--notrevoked",
              "--notexpired",
              "--store",
              candidates.toString()),
          false);
      WalletKeyStore.Material recovered =
          recover(job, candidates, org, enrollmentId, keyRef, attrs);
      if (recovered != null) return recovered;
      var uri = java.net.URI.create(org.caUrl());
      String enrollmentUrl =
          new java.net.URI(
                  uri.getScheme(),
                  enrollmentId + ":" + password,
                  uri.getHost(),
                  uri.getPort(),
                  uri.getPath(),
                  null,
                  null)
              .toASCIIString();
      // maxenrollments=1 prevents a second valid certificate, even if a previous request is still
      // in flight.
      // Retain all BCCSP keys until the signed certificate has been recovered and encrypted.
      run(
          org,
          job,
          Map.of("FABRIC_CA_CLIENT_URL", enrollmentUrl),
          List.of("enroll", "--mspdir", job.resolve("msp").toString()),
          true);
      recovered = recover(job, job.resolve("msp/signcerts"), org, enrollmentId, keyRef, attrs);
      if (recovered == null) throw new IllegalStateException("CA_ENROLLMENT_PENDING_RECOVERY");
      return recovered;
    }
  }

  private WalletKeyStore.Material recover(
      Path job,
      Path certificates,
      Organization org,
      String enrollmentId,
      String keyRef,
      Map<String, String> attrs)
      throws Exception {
    Path keydir = job.resolve("msp/keystore");
    if (!Files.isDirectory(keydir) || !Files.isDirectory(certificates)) return null;
    try (var certs = Files.list(certificates);
        var privateKeys = Files.list(keydir)) {
      var keyFiles = privateKeys.filter(Files::isRegularFile).toList();
      for (Path certfile : certs.filter(Files::isRegularFile).toList()) {
        if (Files.size(certfile) > 131072)
          throw new IllegalStateException("Invalid CA certificate size");
        String pem = Files.readString(certfile);
        var cert = Identities.readX509Certificate(new java.io.StringReader(pem));
        for (Path keyfile : keyFiles) {
          if (Files.size(keyfile) > 131072) throw new IllegalStateException("Invalid CA key size");
          byte[] privatePem = Files.readAllBytes(keyfile);
          try {
            var key =
                Identities.readPrivateKey(
                    new java.io.StringReader(
                        new String(privatePem, java.nio.charset.StandardCharsets.UTF_8)));
            byte[] challenge = new byte[32];
            new java.security.SecureRandom().nextBytes(challenge);
            byte[] signature = Signers.newPrivateKeySigner(key).sign(challenge);
            var verifier = java.security.Signature.getInstance("NONEwithECDSA");
            verifier.initVerify(cert);
            verifier.update(challenge);
            if (!verifier.verify(signature)) continue;
            checked(
                new WalletKeyStore.Material(cert, key, pem, Json.sha(cert.getEncoded())),
                org,
                enrollmentId,
                attrs);
            var material = keys.store(keyRef, pem, privatePem);
            cleanupPlaintext(job);
            return material;
          } finally {
            Arrays.fill(privatePem, (byte) 0);
          }
        }
      }
    }
    return null;
  }

  static WalletKeyStore.Material checked(
      WalletKeyStore.Material material,
      Organization org,
      String enrollmentId,
      Map<String, String> attributes)
      throws Exception {
    var cert = material.certificate();
    cert.checkValidity();
    if (cert.getBasicConstraints() != -1 || !"EC".equals(cert.getPublicKey().getAlgorithm()))
      throw new IllegalStateException("Not a client certificate");
    checkSubject(cert.getSubjectX500Principal().getName(), enrollmentId);
    var factory = CertificateFactory.getInstance("X.509");
    Collection<? extends java.security.cert.Certificate> chain;
    try (var input = Files.newInputStream(Path.of(org.enrollmentRootFile()))) {
      chain = factory.generateCertificates(input);
    }
    var anchors = new HashSet<TrustAnchor>();
    for (var candidate : chain) {
      var ca = (X509Certificate) candidate;
      if (ca.getBasicConstraints() >= 0
          && ca.getSubjectX500Principal().equals(ca.getIssuerX500Principal())) {
        ca.verify(ca.getPublicKey());
        anchors.add(new TrustAnchor(ca, null));
      }
    }
    var selector = new X509CertSelector();
    selector.setCertificate(cert);
    var params = new PKIXBuilderParameters(anchors, selector);
    var collection = new ArrayList<java.security.cert.Certificate>(chain);
    collection.add(cert);
    params.addCertStore(
        CertStore.getInstance("Collection", new CollectionCertStoreParameters(collection)));
    // Channel MSP is authoritative for CRLs; local PKIX checks only the configured enrollment
    // chain.
    params.setRevocationEnabled(false);
    CertPathBuilder.getInstance("PKIX").build(params);
    byte[] extension = cert.getExtensionValue("1.2.3.4.5.6.7.8.1");
    if (extension == null) throw new IllegalStateException("Missing Fabric attributes");
    var actual = Json.MAPPER.readTree(unwrapOctetString(extension)).path("attrs");
    var expected = new HashMap<>(org.attributes());
    expected.putAll(attributes);
    for (var attr : expected.entrySet())
      if (!attr.getValue().equals(actual.path(attr.getKey()).asText()))
        throw new IllegalStateException("Certificate attribute mismatch");
    for (String forbidden :
        List.of(
            "hf.Registrar.Roles",
            "hf.Registrar.DelegateRoles",
            "hf.Registrar.Attributes",
            "hf.Revoker",
            "hf.GenCRL",
            "hf.AffiliationMgr",
            "hf.IntermediateCA"))
      if (actual.has(forbidden)) throw new IllegalStateException("Privileged certificate refused");
    return material;
  }

  static void checkSubject(String distinguishedName, String enrollmentId) throws Exception {
    var names = subjectValues(distinguishedName, "CN");
    var units = subjectValues(distinguishedName, "OU");
    if (!names.equals(Set.of(enrollmentId))
        || !units.contains("client")
        || !Collections.disjoint(units, Set.of("admin", "peer", "orderer")))
      throw new IllegalStateException("Invalid client CN/NodeOU");
  }

  private static Set<String> subjectValues(String distinguishedName, String type) throws Exception {
    var values = new HashSet<String>();
    for (var rdn : new javax.naming.ldap.LdapName(distinguishedName).getRdns()) {
      // Fabric CA encodes affiliation and client OUs in one multi-valued RDN.
      // Rdn.getValue() exposes only one of those values.
      var attribute = rdn.toAttributes().get(type);
      if (attribute == null) continue;
      var entries = attribute.getAll();
      try {
        while (entries.hasMore()) values.add(String.valueOf(entries.next()));
      } finally {
        entries.close();
      }
    }
    return values;
  }

  static byte[] unwrapOctetString(byte[] der) {
    if (der.length < 2 || der[0] != 4) throw new IllegalArgumentException("Invalid extension");
    int length = der[1] & 255, offset = 2;
    if (length > 127) {
      int count = length & 127;
      if (count < 1 || count > 3 || der.length < 2 + count) throw new IllegalArgumentException();
      length = 0;
      for (int i = 0; i < count; i++) length = (length << 8) | (der[offset++] & 255);
    }
    if (length != der.length - offset)
      throw new IllegalArgumentException("Invalid extension length");
    return Arrays.copyOfRange(der, offset, der.length);
  }

  @Override
  public void revoke(Organization org, String enrollmentId) throws Exception {
    if (!enrollmentId.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException();
    secureDirectory(jobs);
    Path job = jobs.resolve(enrollmentId);
    secureDirectory(job);
    try (var channel =
            java.nio.channels.FileChannel.open(
                job.resolve("job.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var lock = channel.tryLock()) {
      if (lock == null) throw new IllegalStateException("CA_JOB_BUSY");
      // Per-version enrollment IDs ensure all certificates for a revoked version are covered.
      run(
          org,
          Path.of(org.registrarHome()),
          Map.of(),
          List.of(
              "revoke", "--revoke.name", enrollmentId, "--revoke.reason", "cessationofoperation"),
          true);
      // A failed/ambiguous revoke is not completion. The administrator later provides a verified
      // CRL receipt.
      Path receipt = Files.createTempDirectory(job, "revocation-");
      secureDirectory(receipt);
      try {
        run(
            org,
            Path.of(org.registrarHome()),
            Map.of(),
            List.of(
                "certificate",
                "list",
                "--id",
                enrollmentId,
                "--revocation",
                "::now",
                "--store",
                receipt.toString()),
            false);
        boolean matched = false;
        try (var files = Files.list(receipt)) {
          for (var file : files.filter(Files::isRegularFile).toList()) {
            var cert =
                Identities.readX509Certificate(new java.io.StringReader(Files.readString(file)));
            if (subjectValues(cert.getSubjectX500Principal().getName(), "CN")
                .equals(Set.of(enrollmentId)))
              matched = true;
          }
        }
        if (!matched) throw new IllegalStateException("CA_REVOCATION_UNCONFIRMED");
        Files.writeString(
            job.resolve("revoked"),
            java.time.Instant.now().toString(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
        cleanupPlaintext(job);
      } finally {
        try (var files = Files.list(receipt)) {
          for (var file : files.toList()) Files.delete(file);
        }
        Files.delete(receipt);
      }
    }
  }

  private static void cleanupPlaintext(Path job) throws Exception {
    Path keydir = job.resolve("msp/keystore");
    if (Files.isDirectory(keydir))
      try (var files = Files.list(keydir)) {
        for (var file : files.filter(Files::isRegularFile).toList()) Files.delete(file);
      }
    Files.deleteIfExists(job.resolve("enrollment.secret"));
    Files.deleteIfExists(job.resolve("fabric-ca-client-config.yaml"));
  }

  private void run(
      Organization org,
      Path home,
      Map<String, String> env,
      List<String> operation,
      boolean tolerateFailure)
      throws Exception {
    var s = settings.read();
    Path binary = Path.of(s.clientBinary());
    if (!Json.sha(Files.readAllBytes(binary)).equals(s.clientSha256()))
      throw new IllegalStateException("CA_CLIENT_DIGEST_MISMATCH");
    var args = new ArrayList<String>();
    args.add(binary.toString());
    args.addAll(operation);
    args.addAll(
        List.of(
            "--home",
            home.toString(),
            "--caname",
            org.caId(),
            "--tls.certfiles",
            org.tlsRootFile()));
    var builder =
        new ProcessBuilder(args)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().keySet().removeIf(k -> k.startsWith("FABRIC_CA_CLIENT_"));
    builder.environment().put("FABRIC_CA_CLIENT_URL", org.caUrl());
    builder.environment().putAll(env);
    var process = builder.start();
    try {
      if (!process.waitFor(20, TimeUnit.SECONDS)) throw new IllegalStateException("CA_TIMEOUT");
      if (process.exitValue() != 0 && !tolerateFailure)
        throw new IllegalStateException("CA_UNAVAILABLE");
    } finally {
      if (process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(2, TimeUnit.SECONDS);
      }
    }
  }

  private static void secureDirectory(Path path) throws Exception {
    Files.createDirectories(path);
    if (!Files.getFileStore(path).supportsFileAttributeView("posix"))
      throw new IllegalStateException("CA runtime requires POSIX secret permissions");
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    if (Files.isSymbolicLink(path))
      throw new IllegalStateException("Symlink secret directory refused");
  }
}
