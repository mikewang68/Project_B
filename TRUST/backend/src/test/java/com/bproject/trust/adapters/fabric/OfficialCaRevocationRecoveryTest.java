package com.bproject.trust.adapters.fabric;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class OfficialCaRevocationRecoveryTest {
  private X509Certificate certificate(String enrollment, int serial) throws Exception {
    var certificate = mock(X509Certificate.class);
    when(certificate.getSubjectX500Principal())
        .thenReturn(new javax.security.auth.x500.X500Principal("CN=" + enrollment));
    when(certificate.getEncoded()).thenReturn(new byte[] {(byte) serial});
    return certificate;
  }

  @Test
  void oneRevokedCertificateCannotHideAnotherValidCertificateForSameEnrollment() throws Exception {
    var first = certificate("version1", 1);
    var second = certificate("version1", 2);
    assertFalse(
        OfficialCaClient.allCertificatesRevoked(
            "version1", List.of(first, second), List.of(first)));
    var revoked = new java.util.ArrayList<>(List.of(first));
    var calls = new AtomicInteger();
    OfficialCaClient.confirmRevocation(
        () -> OfficialCaClient.allCertificatesRevoked("version1", List.of(first, second), revoked),
        () -> {
          calls.incrementAndGet();
          revoked.add(second);
        });
    assertEquals(1, calls.get());
  }

  @Test
  void completeRevocationMatchesCertificateBytesAndRejectsEmptyOrWrongSubject() throws Exception {
    var first = certificate("version1", 1);
    var second = certificate("version1", 2);
    assertTrue(
        OfficialCaClient.allCertificatesRevoked(
            "version1", List.of(first, second), List.of(second, first)));
    assertFalse(OfficialCaClient.allCertificatesRevoked("version1", List.of(), List.of(first)));
    assertFalse(
        OfficialCaClient.allCertificatesRevoked(
            "version1", List.of(first), List.of(certificate("version1", 2))));
    assertFalse(
        OfficialCaClient.allCertificatesRevoked(
            "version1", List.of(first), List.of(certificate("other", 1))));
  }

  @Test
  void confirmedPriorRevocationDoesNotRepeatTheCommand() throws Exception {
    OfficialCaClient.confirmRevocation(
        () -> true, () -> fail("Already confirmed; no second revoke"));
  }

  @Test
  void performedRevocationWithLostResponseIsConfirmedByQuery() throws Exception {
    var done = new AtomicBoolean();
    var reads = new AtomicInteger();
    OfficialCaClient.confirmRevocation(
        () -> {
          reads.incrementAndGet();
          return done.get();
        },
        () -> {
          done.set(true);
          throw new IOException("response lost");
        });
    assertEquals(2, reads.get());
  }

  @Test
  void commandSuccessWithoutConfirmationIsNotCompletion() {
    assertThrows(
        IllegalStateException.class,
        () -> OfficialCaClient.confirmRevocation(() -> false, () -> {}));
  }

  @Test
  void failedInitialQueryDoesNotBlindlyReplayIrreversibleCommand() {
    assertThrows(
        IOException.class,
        () ->
            OfficialCaClient.confirmRevocation(
                () -> {
                  throw new IOException("CA unavailable");
                },
                () -> fail("No blind replay")));
  }
}
