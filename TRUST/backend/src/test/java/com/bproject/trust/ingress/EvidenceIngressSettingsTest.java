package com.bproject.trust.ingress;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bproject.trust.identity.IntegrationSettings;
import com.bproject.trust.provisioning.IdentityProviderSettings;
import com.bproject.trust.shared.json.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class EvidenceIngressSettingsTest {
  @TempDir Path root;
  IntegrationSettings old = mock(IntegrationSettings.class);
  IdentityProviderSettings identities = mock(IdentityProviderSettings.class);
  EvidenceIngressSettings settings;

  static EvidenceIngressSettings.Source source(String types) {
    return new EvidenceIngressSettings.Source(
        "new-source",
        "a".repeat(64),
        "ORG",
        "SOURCE",
        true,
        Set.of("read", "submit"),
        Set.of(types));
  }

  @BeforeEach
  void setup() throws Exception {
    settings = new EvidenceIngressSettings(root.toString(), old, identities);
    when(old.read()).thenReturn(new IntegrationSettings.Settings("", Map.of(), List.of()));
    Files.createDirectories(root.resolve("runtime/secrets"));
  }

  void write(EvidenceIngressSettings.Source... sources) throws Exception {
    Files.writeString(
        root.resolve("runtime/secrets/evidence-ingress.json"),
        Json.write(new EvidenceIngressSettings.Settings(1, true, List.of(sources))));
  }

  @Test
  void defaultsClosedAndDoesNotGrantRetry() throws Exception {
    assertFalse(settings.read().enabled());
    verifyNoInteractions(old, identities);
    write(source("MAINTENANCE_CONFIRMED"));
    var s = settings.read().sources().get(0);
    assertThrows(IngressError.class, () -> s.require("retry"));
    assertThrows(IngressError.class, () -> s.type("DISPATCH"));
    assertTrue(settings.read().enabled());
  }

  @Test
  void refusesWmsNamespaceCredentialsAndDuplicateSources() throws Exception {
    write(source("DISPATCH"));
    assertThrows(IllegalStateException.class, settings::read);
    write(source("MAINTENANCE_CONFIRMED"), source("MAINTENANCE_CONFIRMED"));
    assertThrows(IllegalStateException.class, settings::read);
    write(source("MAINTENANCE_CONFIRMED"));
    when(old.read())
        .thenReturn(
            new IntegrationSettings.Settings(
                "",
                Map.of(),
                List.of(
                    new IntegrationSettings.Service(
                        "wms", "a".repeat(64), "OTHER", "WMS", "C", "W", "O"))));
    assertThrows(IllegalStateException.class, settings::read);
    when(old.read())
        .thenReturn(
            new IntegrationSettings.Settings(
                "",
                Map.of(),
                List.of(
                    new IntegrationSettings.Service(
                        "wms", "b".repeat(64), "ORG", "SOURCE", "C", "W", "O"))));
    assertThrows(IllegalStateException.class, settings::read);
  }

  @Test
  void rejectsIdentityTokenReuseAndFailsWorkerClosed() throws Exception {
    write(source("MAINTENANCE_CONFIRMED"));
    when(identities.configured()).thenReturn(true);
    when(identities.read())
        .thenReturn(
            new IdentityProviderSettings.Settings(
                "",
                "",
                "",
                List.of(
                    new IdentityProviderSettings.Client(
                        "i", "a".repeat(64), "i", "t", Set.of("ORG"), Set.of("read"))),
                Map.of()));
    assertThrows(IllegalStateException.class, settings::read);
    assertTrue(settings.processingSources().isEmpty());
  }

  @Test
  void retryOnlySourceMayContinueItsExistingTasksAndSnapshotIsImmutable() throws Exception {
    var initial = source("MAINTENANCE_CONFIRMED");
    var retry =
        new EvidenceIngressSettings.Source(
            initial.id(),
            initial.tokenSha256(),
            initial.orgId(),
            initial.sourceSystem(),
            true,
            Set.of("read", "retry"),
            initial.allowedEventTypes());
    write(retry);
    assertEquals(List.of(retry), settings.processingSources());
    assertThrows(IngressError.class, () -> retry.require("submit"));
    assertThrows(UnsupportedOperationException.class, () -> settings.read().sources().clear());
    assertThrows(UnsupportedOperationException.class, () -> retry.operations().add("submit"));
  }
}
