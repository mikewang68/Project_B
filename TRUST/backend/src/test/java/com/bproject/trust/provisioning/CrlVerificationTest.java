package com.bproject.trust.provisioning;

import static org.junit.jupiter.api.Assertions.*;

import com.bproject.trust.shared.json.Json;
import java.nio.file.*;
import java.security.cert.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CrlVerificationTest {
  Path fixture(String name) throws Exception {
    return Path.of(getClass().getResource("/identity-public-fixtures/" + name).toURI());
  }

  com.fasterxml.jackson.databind.node.ObjectNode block(byte[] crl) {
    var config =
        Map.of(
            "name", "Org1MSP", "revocation_list", List.of(Base64.getEncoder().encodeToString(crl)));
    var group = Map.of("values", Map.of("MSP", Map.of("value", Map.of("config", config))));
    return Json.MAPPER.valueToTree(
        Map.of(
            "data",
            Map.of(
                "data",
                List.of(
                    Map.of(
                        "payload",
                        Map.of(
                            "header",
                            Map.of("channel_header", Map.of("channel_id", "trust-iam-test")),
                            "data",
                            Map.of(
                                "config",
                                Map.of(
                                    "channel_group",
                                    Map.of(
                                        "groups",
                                        Map.of(
                                            "Application",
                                            Map.of("groups", Map.of("Org1", group))))))))))));
  }

  X509Certificate certificate() throws Exception {
    try (var stream = Files.newInputStream(fixture("client.crt"))) {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(stream);
    }
  }

  @Test
  void verifiesSignedCrlInCorrectCommittedChannelAndMsp() throws Exception {
    var b = block(Files.readAllBytes(fixture("revoked.crl")));
    var c = certificate();
    var ca = fixture("ca.crt");
    assertDoesNotThrow(() -> CrlVerification.verifyConfig(b, "trust-iam-test", "Org1MSP", c, ca));
    assertThrows(
        Exception.class,
        () -> CrlVerification.verifyConfig(b, "trust-wallet-dev", "Org1MSP", c, ca));
    assertThrows(
        Exception.class,
        () -> CrlVerification.verifyConfig(b, "trust-iam-test", "OtherMSP", c, ca));
  }

  @Test
  void rejectsForgedSignatureAndMissingCrl() throws Exception {
    var bytes = Files.readAllBytes(fixture("revoked.crl"));
    bytes[bytes.length - 1] ^= 1;
    var b = block(bytes);
    var c = certificate();
    var ca = fixture("ca.crt");
    assertThrows(
        Exception.class, () -> CrlVerification.verifyConfig(b, "trust-iam-test", "Org1MSP", c, ca));
    assertThrows(
        Exception.class,
        () ->
            CrlVerification.verifyConfig(
                Json.MAPPER.createObjectNode(), "trust-iam-test", "Org1MSP", c, ca));
  }
}
