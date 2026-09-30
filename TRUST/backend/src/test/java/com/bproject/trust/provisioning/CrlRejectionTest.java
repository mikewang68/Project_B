package com.bproject.trust.provisioning;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.Status;
import org.junit.jupiter.api.Test;

class CrlRejectionTest {
  final String denied = "evaluate call to endorser returned error: error validating proposal:"
      + " access denied: channel [trust-iam-dev] creator org [Org1MSP]";

  @Test
  void acceptsObservedFabricProposalAuthenticationRejection() {
    assertTrue(CrlVerification.authenticationRejected(
        Status.FAILED_PRECONDITION.withDescription(denied).asRuntimeException(),
        "trust-iam-dev", "Org1MSP"));
    assertTrue(CrlVerification.authenticationRejected(
        Status.UNAUTHENTICATED.asRuntimeException(), "trust-iam-dev", "Org1MSP"));
  }

  @Test
  void rejectsAmbiguousErrorsAndUnrelatedChannelOrOrganization() {
    for (String message : new String[] {"access denied", "chaincode unavailable", denied + " timeout"}) {
      assertFalse(CrlVerification.authenticationRejected(
          Status.FAILED_PRECONDITION.withDescription(message).asRuntimeException(),
          "trust-iam-dev", "Org1MSP"));
    }
    assertFalse(CrlVerification.authenticationRejected(
        Status.UNAVAILABLE.withDescription(denied).asRuntimeException(), "trust-iam-dev", "Org1MSP"));
    assertFalse(CrlVerification.authenticationRejected(
        Status.FAILED_PRECONDITION.withDescription(denied).asRuntimeException(), "other-channel", "Org1MSP"));
    assertFalse(CrlVerification.authenticationRejected(
        Status.FAILED_PRECONDITION.withDescription(denied).asRuntimeException(), "trust-iam-dev", "Org2MSP"));
  }
}
