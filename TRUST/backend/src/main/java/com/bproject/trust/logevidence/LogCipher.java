package com.bproject.trust.logevidence;

import com.bproject.trust.ingress.IngressError;
import com.bproject.trust.shared.json.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.stereotype.Component;

@Component
public class LogCipher {
  private final LogSettings settings;

  public LogCipher(LogSettings settings) {
    this.settings = settings;
  }

  public record Encrypted(String keyId, String ciphertext, String ciphertextHash) {}

  private byte[] key(String id) throws Exception {
    var config = settings.read();
    var ring = Json.MAPPER.readTree(Files.readString(Path.of(config.keyRingFile())));
    byte[] value = Base64.getDecoder().decode(ring.path("keys").path(id).asText());
    if (value.length != 32) throw new IllegalArgumentException();
    return value;
  }

  public Encrypted encrypt(String eventKey, String hash, String text) {
    try {
      String id = settings.read().activeKeyId();
      byte[] nonce = new byte[12];
      new SecureRandom().nextBytes(nonce);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(key(id), "AES"), new GCMParameterSpec(128, nonce));
      c.updateAAD((eventKey + "\n" + hash + "\n" + id).getBytes(StandardCharsets.UTF_8));
      String envelope =
          Json.write(
              Map.of(
                  "schemaVersion",
                  1,
                  "algorithm",
                  "AES-256-GCM",
                  "keyId",
                  id,
                  "nonce",
                  Base64.getEncoder().encodeToString(nonce),
                  "ciphertext",
                  Base64.getEncoder()
                      .encodeToString(c.doFinal(text.getBytes(StandardCharsets.UTF_8)))));
      return new Encrypted(id, envelope, LogInput.hash(envelope));
    } catch (Exception e) {
      throw new IngressError(503, "ARCHIVE_KEY_UNAVAILABLE", "归档密钥不可用");
    }
  }

  public String decrypt(String eventKey, String hash, String envelope, String expectedHash) {
    try {
      if (!LogInput.hash(envelope).equals(expectedHash)) throw new IllegalArgumentException();
      var n = Json.MAPPER.readTree(envelope);
      String id = n.path("keyId").asText();
      if (n.path("schemaVersion").asInt() != 1
          || !"AES-256-GCM".equals(n.path("algorithm").asText()))
        throw new IllegalArgumentException();
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key(id), "AES"),
          new GCMParameterSpec(128, Base64.getDecoder().decode(n.path("nonce").asText())));
      c.updateAAD((eventKey + "\n" + hash + "\n" + id).getBytes(StandardCharsets.UTF_8));
      String text =
          new String(
              c.doFinal(Base64.getDecoder().decode(n.path("ciphertext").asText())),
              StandardCharsets.UTF_8);
      if (!LogInput.hash(text).equals(hash)) throw new IllegalArgumentException();
      return text;
    } catch (Exception e) {
      throw new IngressError(503, "ARCHIVE_RECOVERY_FAILED", "原文恢复或完整性核验失败");
    }
  }
}
