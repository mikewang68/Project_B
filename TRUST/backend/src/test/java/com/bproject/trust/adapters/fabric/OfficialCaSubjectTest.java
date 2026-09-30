package com.bproject.trust.adapters.fabric;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OfficialCaSubjectTest {
  @Test
  void acceptsFabricCaClientWithAffiliationInOneRdn() {
    assertDoesNotThrow(
        () -> OfficialCaClient.checkSubject(
            "CN=iam_test_v1,OU=org1+OU=client+OU=business,O=Hyperledger,C=US", "iam_test_v1"));
    assertDoesNotThrow(
        () -> OfficialCaClient.checkSubject("CN=iam_test_v1,OU=client,OU=business", "iam_test_v1"));
  }

  @Test
  void rejectsPrivilegedOuAnywhereInMultiValuedRdn() {
    for (String role : new String[] {"admin", "peer", "orderer"}) {
      assertThrows(IllegalStateException.class,
          () -> OfficialCaClient.checkSubject(
              "CN=iam_test_v1,OU=business+OU=client+OU=" + role, "iam_test_v1"));
    }
  }

  @Test
  void rejectsWrongAmbiguousOrMissingClientIdentity() {
    for (String subject : new String[] {
        "CN=other,OU=client", "CN=iam_test_v1+CN=other,OU=client", "CN=iam_test_v1,OU=business"}) {
      assertThrows(IllegalStateException.class,
          () -> OfficialCaClient.checkSubject(subject, "iam_test_v1"));
    }
  }
}
