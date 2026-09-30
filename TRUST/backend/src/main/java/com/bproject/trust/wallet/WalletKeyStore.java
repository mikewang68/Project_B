package com.bproject.trust.wallet;

import com.bproject.trust.shared.json.Json;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.hyperledger.fabric.client.identity.Identities;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Immutable, provisioned certificate versions; encrypted PKCS#8 PEM, never browser uploads. */
@Component
public class WalletKeyStore {
  private final Path root;

  public record Material(
      X509Certificate certificate, PrivateKey key, String pem, String fingerprint) {}

  public WalletKeyStore(@Value("${trust.root}") String root) {
    this.root = Path.of(root, "runtime/secrets/wallets");
  }

  public boolean contains(String reference) {
    return reference != null
        && reference.matches("[A-Za-z0-9_-]{1,100}")
        && Files.isDirectory(root.resolve(reference));
  }

  /**
   * Atomic import into the existing encrypted custody format. Master key is operator provisioned.
   */
  public synchronized Material store(String reference, String certificatePem, byte[] privateKeyPem)
      throws Exception {
    if (reference == null || !reference.matches("[A-Za-z0-9_-]{1,100}"))
      throw new IllegalArgumentException("Invalid key reference");
    if (contains(reference)) return load(reference);
    Path base = root.toRealPath();
    if (!Files.getFileStore(base).supportsFileAttributeView("posix"))
      throw new IllegalStateException("Custody import requires a POSIX secret directory");
    var permissions = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");
    if (!Files.getPosixFilePermissions(base).equals(permissions))
      throw new IllegalStateException("Insecure custody permissions");
    byte[] master = Files.readAllBytes(base.resolve("master.key"));
    if (master.length != 32) throw new IllegalStateException("Invalid custody master key");
    Path staging =
        Files.createTempDirectory(
            base,
            "import-",
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(permissions));
    try {
      byte[] nonce = new byte[12];
      new java.security.SecureRandom().nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(master, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(reference.getBytes(StandardCharsets.UTF_8));
      byte[] payload = cipher.doFinal(privateKeyPem), encrypted = new byte[12 + payload.length];
      System.arraycopy(nonce, 0, encrypted, 0, 12);
      System.arraycopy(payload, 0, encrypted, 12, payload.length);
      Files.writeString(staging.resolve("certificate.pem"), certificatePem);
      Files.write(staging.resolve("private-key.enc"), encrypted);
      for (String name : new String[] {"certificate.pem", "private-key.enc"}) {
        Files.setPosixFilePermissions(
            staging.resolve(name),
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        try (var ch =
            java.nio.channels.FileChannel.open(
                staging.resolve(name), java.nio.file.StandardOpenOption.WRITE)) {
          ch.force(true);
        }
      }
      Files.move(staging, base.resolve(reference), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      return load(reference);
    } finally {
      Arrays.fill(master, (byte) 0);
      Arrays.fill(privateKeyPem, (byte) 0);
      if (Files.exists(staging)) {
        Files.deleteIfExists(staging.resolve("certificate.pem"));
        Files.deleteIfExists(staging.resolve("private-key.enc"));
        Files.delete(staging);
      }
    }
  }

  public Material load(String reference) throws Exception {
    if (reference == null || !reference.matches("[A-Za-z0-9_-]{1,100}"))
      throw new IllegalArgumentException("无效密钥引用");
    Path base = root.toRealPath(), dir = base.resolve(reference).toRealPath();
    if (!base.resolve("master.key").toRealPath().getParent().equals(base))
      throw new IllegalArgumentException("主密钥文件越界");
    if (!dir.startsWith(base) || !dir.getParent().equals(base))
      throw new IllegalArgumentException("密钥引用越界");
    for (String name : new String[] {"certificate.pem", "private-key.enc"})
      if (!dir.resolve(name).toRealPath().getParent().equals(dir)
          || Files.size(dir.resolve(name)) > 131072)
        throw new IllegalArgumentException("密钥文件越界或过大");
    String pem = Files.readString(dir.resolve("certificate.pem"));
    var cert = Identities.readX509Certificate(new StringReader(pem));
    cert.checkValidity();
    byte[] encrypted = Files.readAllBytes(dir.resolve("private-key.enc"));
    byte[] master = Files.readAllBytes(base.resolve("master.key"));
    if (master.length != 32 || encrypted.length < 29) throw new IllegalArgumentException("密钥材料不完整");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(master, "AES"),
        new GCMParameterSpec(128, Arrays.copyOf(encrypted, 12)));
    cipher.updateAAD(reference.getBytes(StandardCharsets.UTF_8));
    byte[] plain = cipher.doFinal(Arrays.copyOfRange(encrypted, 12, encrypted.length));
    try {
      var key =
          Identities.readPrivateKey(new StringReader(new String(plain, StandardCharsets.UTF_8)));
      String algorithm =
          switch (key.getAlgorithm()) {
            case "EC" -> "SHA256withECDSA";
            case "RSA" -> "SHA256withRSA";
            default -> throw new IllegalArgumentException("不支持的签名算法");
          };
      var signer = Signature.getInstance(algorithm);
      signer.initSign(key);
      signer.update("trust-key-check".getBytes(StandardCharsets.UTF_8));
      byte[] proof = signer.sign();
      signer.initVerify(cert);
      signer.update("trust-key-check".getBytes(StandardCharsets.UTF_8));
      if (!signer.verify(proof)) throw new IllegalArgumentException("证书与私钥不匹配");
      return new Material(cert, key, pem, Json.sha(cert.getEncoded()));
    } finally {
      Arrays.fill(master, (byte) 0);
      Arrays.fill(plain, (byte) 0);
    }
  }
}
