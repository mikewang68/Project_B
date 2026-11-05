package com.bdemo.iam;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bdemo.iam.config.IamIsolationGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class IdentityIsolationGuardTest {
  @TempDir Path temporary;

  MockEnvironment valid() throws Exception {
    String root = temporary.resolve("IAM").toString(),
        url = "jdbc:postgresql://db.example.invalid:5544/identity_fixture?currentSchema=iam";
    Path file = temporary.resolve("instances.json");
    Files.writeString(
        file,
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "schemaVersion",1,"blockedPorts",List.of(18000),
                    "instances",
                    Map.of(
                        "test",
                        Map.of(
                            "root",
                            root,
                            "databaseUrl",
                            url,
                            "appRole",
                            "fixture_app",
                            "port",
                            18001,
                            "deploymentProfile","STANDARD",
                            "databaseHost","db.example.invalid",
                            "databasePort",5544,
                            "databaseName","identity_fixture")))));
    return new MockEnvironment()
        .withProperty("iam.root", root)
        .withProperty("iam.instance-allowlist", file.toString())
        .withProperty("iam.instance", "test")
        .withProperty("spring.datasource.url", url)
        .withProperty("spring.datasource.username", "fixture_app")
        .withProperty("server.address", "127.0.0.1")
        .withProperty("server.port", "18001")
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
          {"spring.datasource.url", "jdbc:postgresql://db.example.invalid:5544/other_fixture"},
          {"server.port", "18000"},
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

  void item(MockEnvironment env,String field,Object value)throws Exception {
    Path file=Path.of(env.getProperty("iam.instance-allowlist"));
    var mapper=new ObjectMapper();var config=mapper.readTree(Files.readString(file));
    var instance=(com.fasterxml.jackson.databind.node.ObjectNode)config.path("instances").path("test");
    if(value==null)instance.remove(field);else instance.set(field,mapper.valueToTree(value));
    Files.writeString(file,mapper.writeValueAsString(config));
  }

  @Test void requiresExplicitProfileHostDatabaseRoleAndBlockedPortPolicy()throws Exception {
    for(String field:List.of("deploymentProfile","databaseHost","databasePort","databaseName","appRole")) {
      var env=valid();item(env,field,null);
      assertThrows(IllegalStateException.class,()->new IamIsolationGuard().postProcessEnvironment(env,null));
    }
    var unknownProfile=valid();item(unknownProfile,"deploymentProfile","UNKNOWN");
    assertThrows(IllegalStateException.class,()->new IamIsolationGuard().postProcessEnvironment(unknownProfile,null));
    var env=valid();var mapper=new ObjectMapper();Path file=Path.of(env.getProperty("iam.instance-allowlist"));
    var config=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(Files.readString(file));
    config.remove("blockedPorts");Files.writeString(file,mapper.writeValueAsString(config));
    var missingPolicy=env;
    assertThrows(IllegalStateException.class,()->new IamIsolationGuard().postProcessEnvironment(missingPolicy,null));
  }

  @Test void rejectsAnExplicitlyBlockedPortEvenWhenEnvironmentMatches()throws Exception {
    var env=valid().withProperty("server.port","18000");item(env,"port",18000);
    assertThrows(IllegalStateException.class,()->new IamIsolationGuard().postProcessEnvironment(env,null));
  }

  @Test void isolatedProfileRequiresPositiveOidBeforeConnecting()throws Exception {
    var env=valid();item(env,"deploymentProfile","ISOLATED");
    assertThrows(IllegalStateException.class,()->new IamIsolationGuard().postProcessEnvironment(env,null));
  }

  @Test void isolatedProfileVerifiesTheActualOid()throws Exception {
    var env=valid().withProperty("spring.datasource.password","offline-fixture");
    item(env,"deploymentProfile","ISOLATED");item(env,"databaseOid",12345L);
    var connection=mock(Connection.class);var statement=mock(Statement.class);var result=mock(ResultSet.class);
    when(connection.createStatement()).thenReturn(statement);when(statement.executeQuery(anyString())).thenReturn(result);
    when(result.next()).thenReturn(true);when(result.getLong(1)).thenReturn(12345L,99999L);
    try(var driver=mockStatic(DriverManager.class)) {
      driver.when(()->DriverManager.getConnection(anyString(),anyString(),anyString())).thenReturn(connection);
      assertDoesNotThrow(()->new IamIsolationGuard().postProcessEnvironment(env,null));
      assertThrows(IllegalStateException.class,()->new IamIsolationGuard().postProcessEnvironment(env,null));
    }
  }

  @Test void restoreProfileCannotSendIdentityAuditOrLogoutTraffic()throws Exception {
    for(String property:List.of("iam.identity.delivery-enabled","iam.audit.public.enabled","sys.audit.public.enabled","app.sso.logout-endpoints")) {
      var env=valid().withProperty("iam.identity.delivery-enabled","false")
          .withProperty("iam.audit.public.enabled","false").withProperty("sys.audit.public.enabled","false");
      item(env,"deploymentProfile","RESTORE");item(env,"databaseOid",12345L);
      env.withProperty(property,property.equals("app.sso.logout-endpoints")?"registered-callback":"true");
      assertThrows(IllegalStateException.class,()->new IamIsolationGuard().postProcessEnvironment(env,null));
    }
  }
}
