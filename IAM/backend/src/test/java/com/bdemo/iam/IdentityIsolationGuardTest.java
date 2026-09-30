package com.bdemo.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.bdemo.iam.config.IamIsolationGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class IdentityIsolationGuardTest {
  @TempDir Path temporary;

  MockEnvironment valid() throws Exception {
    String root = temporary.resolve("IAM").toString(),
        url = "jdbc:postgresql://127.0.0.1:25432/iam_identity_dev_test?currentSchema=iam";
    Path file = temporary.resolve("instances.json");
    Files.writeString(
        file,
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "schemaVersion",
                    1,
                    "instances",
                    Map.of(
                        "test",
                        Map.of(
                            "root",
                            root,
                            "databaseUrl",
                            url,
                            "appRole",
                            "iam_identity_test_app",
                            "port",
                            28186)))));
    return new MockEnvironment()
        .withProperty("iam.root", root)
        .withProperty("iam.instance-allowlist", file.toString())
        .withProperty("iam.instance", "test")
        .withProperty("spring.datasource.url", url)
        .withProperty("spring.datasource.username", "iam_identity_test_app")
        .withProperty("server.address", "127.0.0.1")
        .withProperty("server.port", "28186")
        .withProperty("app.jwt.secret", "offline-jwt-test-value-0123456789abcdef")
        .withProperty("iam.identity.idempotency-secret", "offline-hmac-test-value-0123456789abcdef")
        .withProperty("iam.identity.config", temporary.resolve("identity.json").toString());
  }

  @Test
  void acceptsExplicitIndependentInstance() {
    assertDoesNotThrow(() -> new IamIsolationGuard().postProcessEnvironment(valid(), null));
  }

  @Test
  void rejectsWrongDatabasePublishedPortAndSharedSecrets() throws Exception {
    for (var invalid :
        new String[][] {
          {"spring.datasource.url", "jdbc:postgresql://127.0.0.1:25432/project_b"},
          {"server.port", "18091"},
          {"server.address", "0.0.0.0"},
          {"iam.identity.idempotency-secret", "offline-jwt-test-value-0123456789abcdef"},
          {"app.jwt.secret", "REPLACE_WITH_RANDOM_VALUE_0123456789"}
        }) {
      var e = valid().withProperty(invalid[0], invalid[1]);
      assertThrows(
          IllegalStateException.class,
          () -> new IamIsolationGuard().postProcessEnvironment(e, null));
    }
  }
}
