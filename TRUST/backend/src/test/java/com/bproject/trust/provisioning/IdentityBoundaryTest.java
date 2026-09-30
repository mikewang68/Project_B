package com.bproject.trust.provisioning;

import static org.junit.jupiter.api.Assertions.*;

import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdentityBoundaryTest {
  final IdentityProviderSettings.Client caller =
      new IdentityProviderSettings.Client(
          "iam",
          "unused",
          "issuer",
          "tenant",
          Set.of("ORG1"),
          Set.of("read", "create", "disable", "rotate", "revoke"));

  FabricIdentityService.Command command(
      String issuer, String tenant, String org, String state, String action, long rev) {
    return new FabricIdentityService.Command(
        issuer, tenant, "stable-user-123", org, rev, state, action);
  }

  @Test
  void acceptsStableUserWithinScope() {
    assertDoesNotThrow(
        () ->
            FabricIdentityService.validate(
                caller,
                "test-request-key-1234",
                command("issuer", "tenant", "ORG1", "ACTIVE", "SYNC", 1)));
  }

  @Test
  void rejectsCrossTenantIssuerAndOrganization() {
    for (var c :
        new FabricIdentityService.Command[] {
          command("other", "tenant", "ORG1", "ACTIVE", "SYNC", 1),
          command("issuer", "other", "ORG1", "ACTIVE", "SYNC", 1),
          command("issuer", "tenant", "ORG2", "ACTIVE", "SYNC", 1)
        }) {
      var error =
          assertThrows(
              ApiError.class,
              () -> FabricIdentityService.validate(caller, "test-request-key-1234", c));
      assertEquals(403, error.status);
    }
  }

  @Test
  void readCredentialCannotProvision() {
    var read =
        new IdentityProviderSettings.Client(
            "read", "unused", "issuer", "tenant", Set.of("ORG1"), Set.of("read"));
    assertEquals(
        403,
        assertThrows(
                ApiError.class,
                () ->
                    FabricIdentityService.validate(
                        read,
                        "test-request-key-1234",
                        command("issuer", "tenant", "ORG1", "ACTIVE", "SYNC", 1)))
            .status);
  }

  @Test
  void deletionRequiresRevokeWhilePlatformDisableDoesNot() {
    var disable = new IdentityProviderSettings.Client(
        "limited", "unused", "issuer", "tenant", Set.of("ORG1"), Set.of("read", "disable"));
    assertDoesNotThrow(() -> FabricIdentityService.validate(disable, "test-request-key-1234",
        command("issuer", "tenant", "ORG1", "DISABLED", "SYNC", 2)));
    for (String action : new String[] {"SYNC", "REVOKE", "ROTATE"}) {
      assertEquals(403, assertThrows(ApiError.class,
          () -> FabricIdentityService.validate(disable, "test-request-key-1234",
              command("issuer", "tenant", "ORG1", "DELETED", action, 2))).status);
    }
    var revoke = new IdentityProviderSettings.Client(
        "revoker", "unused", "issuer", "tenant", Set.of("ORG1"), Set.of("read", "revoke"));
    assertDoesNotThrow(() -> FabricIdentityService.validate(revoke, "test-request-key-1234",
        command("issuer", "tenant", "ORG1", "DELETED", "SYNC", 2)));
  }

  @Test
  void rejectsPrivilegedBrowserCertificateAttributes() {
    assertThrows(
        Exception.class,
        () ->
            Json.MAPPER.readValue(
                "{\"issuer\":\"issuer\",\"tenant\":\"tenant\",\"userId\":\"u\",\"orgId\":\"ORG1\",\"revision\":1,\"desiredState\":\"ACTIVE\",\"action\":\"SYNC\",\"mspId\":\"AdminMSP\",\"attributes\":{\"hf.Registrar.Roles\":\"admin\"}}",
                FabricIdentityService.Command.class));
  }

  @Test
  void rejectsConflictingLifecycleAndInvalidRevision() {
    assertThrows(
        ApiError.class,
        () ->
            FabricIdentityService.validate(
                caller,
                "test-request-key-1234",
                command("issuer", "tenant", "ORG1", "ACTIVE", "REVOKE", 1)));
    assertThrows(
        ApiError.class,
        () ->
            FabricIdentityService.validate(
                caller,
                "test-request-key-1234",
                command("issuer", "tenant", "ORG1", "DISABLED", "ROTATE", 1)));
    assertThrows(
        ApiError.class,
        () ->
            FabricIdentityService.validate(
                caller,
                "test-request-key-1234",
                command("issuer", "tenant", "ORG1", "ACTIVE", "SYNC", 0)));
  }
}
