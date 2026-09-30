package com.bproject.trust.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class IsolationGuardTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;

  MockEnvironment valid() throws Exception {
    String root = temporary.resolve("TRUST").toString();
    java.nio.file.Files.createDirectories(java.nio.file.Path.of(root, "runtime/secrets"));
    java.nio.file.Files.writeString(
        java.nio.file.Path.of(root, "runtime/secrets/integration.json"),
        "{\"iamUrl\":\"http://127.0.0.1:28184/api/v1/iam\",\"iamMode\":\"real\",\"orgMap\":{},\"services\":[]}");
    var instance =
        new IdentityInstance(
            root,
            "jdbc:opengauss://127.0.0.1:25432/trust_iam_dev_test",
            "trust_iam_test_app",
            "trust_iam_test_migrate",
            "trust-iam-dev-test",
            28187,
            "org1-user1-v1");
    var file = temporary.resolve("instances.json");
    java.nio.file.Files.writeString(
        file,
        com.bproject.trust.shared.json.Json.write(
            new IdentityInstance.Allowlist(1, java.util.Map.of("test", instance))));
    return new MockEnvironment()
        .withProperty("trust.isolation-enabled", "true")
        .withProperty("trust.root", root)
        .withProperty("trust.instance-allowlist", file.toString())
        .withProperty("trust.instance", "test")
        .withProperty(
            "spring.datasource.url", "jdbc:opengauss://127.0.0.1:25432/trust_iam_dev_test")
        .withProperty("spring.datasource.username", "trust_iam_test_app")
        .withProperty("trust.fabric-channel", "trust-iam-dev-test")
        .withProperty("trust.fabric-query-key-ref", "org1-user1-v1")
        .withProperty("server.address", "127.0.0.1")
        .withProperty("server.port", "28187")
        .withProperty("spring.liquibase.enabled", "false");
  }

  @Test
  void protectsLegacyRootsForAnyAccount() {
    for (String user : new String[] {"operator-a", "operator-b"}) {
      String legacy = "/home/" + user + "/projects/b-project-trust";
      assertTrue(IdentityInstance.protectedRoot(legacy));
      assertTrue(IdentityInstance.protectedRoot(legacy + "/TRUST"));
      assertTrue(IdentityInstance.protectedRoot(legacy + "-backup/TRUST"));
      assertTrue(IdentityInstance.protectedRoot(legacy + "/line\nbreak/TRUST"));
    }
    assertTrue(IdentityInstance.protectedRoot("/data/app/trust/TRUST"));
    assertTrue(IdentityInstance.protectedRoot("/srv/trust-wallet-remediation/TRUST"));
    assertFalse(IdentityInstance.protectedRoot("/srv/b-project-identity/TRUST"));
    assertFalse(IdentityInstance.protectedRoot("/home/operator-a/isolated/TRUST"));
  }

  @Test
  void acceptsDedicatedConfiguration() {
    assertDoesNotThrow(() -> new IsolationGuard().postProcessEnvironment(valid(), null));
  }

  @Test
  void rejectsProtectedResourcesBeforeBeansAndMigrations() throws Exception {
    for (String[] forbidden :
        new String[][] {
          {"spring.datasource.url", "jdbc:opengauss://127.0.0.1:25432/trust"},
          {"spring.datasource.url", "jdbc:opengauss://127.0.0.1:25432/trust_test"},
          {"spring.datasource.username", "trust_app"},
          {"trust.fabric-channel", "trust"},
          {"trust.dev-quick-login.enabled", "true"},
          {"server.port", "28183"},
          {"trust.instance", "unknown"},
          {"server.port", "28182"},
          {"server.address", "0.0.0.0"},
          {"spring.liquibase.enabled", "true"},
          {"trust.root", "/data/app/trust"},
          {"trust.fabric-query-key-ref", ""}
        }) {
      var env = valid().withProperty(forbidden[0], forbidden[1]);
      assertThrows(
          IllegalStateException.class,
          () -> new IsolationGuard().postProcessEnvironment(env, null),
          forbidden[0]);
    }
  }
}
