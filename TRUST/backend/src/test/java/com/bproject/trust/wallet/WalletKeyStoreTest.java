package com.bproject.trust.wallet;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class WalletKeyStoreTest {
  @TempDir Path root;
  Path dir;

  @BeforeEach
  void provision() throws Exception {
    dir = root.resolve("runtime/secrets/wallets/version1");
    Files.createDirectories(dir);
    var p =
        new ProcessBuilder(
                "openssl",
                "req",
                "-x509",
                "-newkey",
                "ec",
                "-pkeyopt",
                "ec_paramgen_curve:P-256",
                "-nodes",
                "-subj",
                "/CN=wallet-test",
                "-days",
                "1",
                "-keyout",
                root.resolve("key.pem").toString(),
                "-out",
                dir.resolve("certificate.pem").toString())
            .redirectErrorStream(true)
            .start();
    p.getInputStream().readAllBytes();
    assertEquals(0, p.waitFor());
    byte[] master = new byte[32], nonce = new byte[12];
    new java.security.SecureRandom().nextBytes(master);
    new java.security.SecureRandom().nextBytes(nonce);
    var c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(master, "AES"), new GCMParameterSpec(128, nonce));
    c.updateAAD("version1".getBytes(StandardCharsets.UTF_8));
    byte[] enc = c.doFinal(Files.readAllBytes(root.resolve("key.pem"))),
        out = Arrays.copyOf(nonce, 12 + enc.length);
    System.arraycopy(enc, 0, out, 12, enc.length);
    Files.write(dir.getParent().resolve("master.key"), master);
    Files.write(dir.resolve("private-key.enc"), out);
  }

  @Test
  void loadsMatchingEncryptedKeyAndRejectsTampering() throws Exception {
    var store = new WalletKeyStore(root.toString());
    assertEquals(64, store.load("version1").fingerprint().length());
    byte[] enc = Files.readAllBytes(dir.resolve("private-key.enc"));
    enc[enc.length - 1] ^= 1;
    Files.write(dir.resolve("private-key.enc"), enc);
    assertThrows(Exception.class, () -> store.load("version1"));
  }

  @Test
  void rejectsTraversalAndSymlinkEscape() throws Exception {
    var store = new WalletKeyStore(root.toString());
    assertThrows(Exception.class, () -> store.load("../version1"));
    Files.createSymbolicLink(dir.getParent().resolve("escape"), root);
    assertThrows(Exception.class, () -> store.load("escape"));
  }

  @Test
  void rejectsRealCertificateWithDifferentPrivateKey() throws Exception {
    openssl("req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256",
        "-nodes", "-subj", "/CN=another-wallet", "-days", "1", "-keyout", "other-key.pem",
        "-out", dir.resolve("certificate.pem").toString());
    var failure = assertThrows(IllegalArgumentException.class,
        () -> new WalletKeyStore(root.toString()).load("version1"));
    assertEquals("证书与私钥不匹配", failure.getMessage());
  }

  @Test
  void rejectsRealExpiredCertificateBeforeSigning() throws Exception {
    Files.writeString(root.resolve("index.txt"), "");
    Files.writeString(root.resolve("serial"), "1000\n");
    Files.writeString(root.resolve("ca.cnf"), """
        [ca]
        default_ca = test_ca
        [test_ca]
        database = index.txt
        serial = serial
        new_certs_dir = .
        default_md = sha256
        policy = names
        [names]
        commonName = supplied
        """);
    openssl("req", "-new", "-key", "key.pem", "-subj", "/CN=wallet-test", "-out", "expired.csr");
    openssl("ca", "-batch", "-selfsign", "-config", "ca.cnf", "-keyfile", "key.pem",
        "-cert", dir.resolve("certificate.pem").toString(), "-in", "expired.csr",
        "-startdate", "20000101000000Z", "-enddate", "20010101000000Z", "-out", "expired.pem");
    Files.copy(root.resolve("expired.pem"), dir.resolve("certificate.pem"), StandardCopyOption.REPLACE_EXISTING);
    assertThrows(java.security.cert.CertificateExpiredException.class,
        () -> new WalletKeyStore(root.toString()).load("version1"));
  }

  void openssl(String... arguments) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("openssl");
    command.addAll(java.util.List.of(arguments));
    var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
  }
}
