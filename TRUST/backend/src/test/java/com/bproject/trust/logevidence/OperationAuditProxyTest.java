package com.bproject.trust.logevidence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bproject.trust.adapters.iam.SimulatedIamClient;
import com.bproject.trust.identity.*;
import com.bproject.trust.ports.PlatformIdentity;
import com.bproject.trust.shared.json.Json;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Real transaction-created CGLIB bean and security chain; JDBC/IAM are bounded test doubles. */
@SpringJUnitConfig(OperationAuditProxyTest.Config.class)
@WebAppConfiguration
class OperationAuditProxyTest {
  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @EnableTransactionManagement(proxyTargetClass = true)
  @Import(Security.class)
  static class Config {
    @Bean
    IntegrationSettings settings() {
      var settings = mock(IntegrationSettings.class);
      when(settings.read())
          .thenReturn(
              new IntegrationSettings.Settings(
                  "", "real", false, Map.of("ORG", "TRUST_ORG"), List.of()));
      return settings;
    }

    @Bean(name = "iamClient")
    PlatformIdentity iam() {
      return mock(PlatformIdentity.class);
    }

    @Bean
    SimulatedIamClient simulated() {
      return mock(SimulatedIamClient.class);
    }

    @Bean
    JdbcTemplate db() {
      return mock(JdbcTemplate.class);
    }

    @Bean
    CurrentIdentity currentIdentity(JdbcTemplate db) { return new CurrentIdentity(db); }

    @Bean
    LogSettings logSettings() {
      return mock(LogSettings.class);
    }

    @Bean
    PlatformTransactionManager transactionManager() {
      return mock(PlatformTransactionManager.class);
    }

    @Bean
    TransactionTemplate transactions(PlatformTransactionManager manager) {
      return new TransactionTemplate(manager);
    }

    @Bean
    LogOperations operations(JdbcTemplate db, TransactionTemplate tx, LogSettings settings) {
      return new LogOperations(db, tx, settings);
    }

    @Bean
    LogDeliveryWorker delivery(JdbcTemplate db, LogSettings settings) {
      return new LogDeliveryWorker(db, settings);
    }

    @Bean
    Reads reads() {
      return new Reads();
    }

    @Bean
    static org.springframework.context.support.PropertySourcesPlaceholderConfigurer properties() {
      var config = new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
      var properties = new Properties();
      properties.setProperty("trust.root", ".");
      config.setProperties(properties);
      return config;
    }
  }

  @RestController
  static class Reads {
    @GetMapping("/api/v1/audit/settings")
    Object read() {
      return Map.of("ok", true);
    }
  }

  @Autowired WebApplicationContext context;

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("iamClient")
  PlatformIdentity iam;

  @Autowired JdbcTemplate db;
  @Autowired LogOperations operations;
  @Autowired LogSettings settings;
  @Autowired LogDeliveryWorker delivery;
  @TempDir Path root;
  final List<Object[]> writes = new ArrayList<>();
  MockMvc http;

  @BeforeEach
  void setup() {
    reset(iam, db, settings);
    writes.clear();
    assertTrue(
        AopUtils.isCglibProxy(operations),
        "The production transaction advisor must create the bean proxy");
    when(iam.current("synthetic-session-token"))
        .thenReturn(
            new PlatformIdentity.User(
                "real-user", "user", List.of("ORG"), Set.of("trust:operations:read")));
    doAnswer(
            call -> {
              writes.add((Object[]) call.getRawArguments()[1]);
              return 1;
            })
        .when(db)
        .update(startsWith("INSERT INTO audit_delivery"), any(Object[].class));
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  MockHttpSession session() {
    var session = new MockHttpSession();
    session.setAttribute("IAM_TOKEN", "synthetic-session-token");
    session.setAttribute("IAM_USER", "real-user");
    session.setAttribute("IAM_ORG", "TRUST_ORG");
    return session;
  }

  @Test
  void ownedPageHeaderPassesTheRealProxyAndPersistsBothRequestOutcomes() throws Exception {
    String id = UUID.randomUUID().toString();
    when(db.queryForList(anyString(), eq(id)))
        .thenReturn(List.of(Map.of("actor", "real-user", "org_id", "TRUST_ORG")));
    http.perform(
            get("/api/v1/audit/settings").session(session()).header("X-TRUST-Page-Action-Id", id))
        .andExpect(status().isOk());
    assertEquals(2, writes.size());
    assertEquals(List.of("STARTED", "SUCCESS"), writes.stream().map(values -> values[5]).toList());
    for (Object[] values : writes) {
      assertEquals("real-user", values[2]);
      assertEquals("REQUEST", values[6]);
      assertEquals(id, values[11]);
    }
  }

  @Test
  void crossUserAndMissingPageHeadersRemainDeniedThroughTheRealProxy() throws Exception {
    String cross = UUID.randomUUID().toString(), missing = UUID.randomUUID().toString();
    when(db.queryForList(anyString(), eq(cross)))
        .thenReturn(List.of(Map.of("actor", "other-user", "org_id", "TRUST_ORG")));
    when(db.queryForList(anyString(), eq(missing))).thenReturn(List.of());
    for (String id : List.of(cross, missing)) {
      http.perform(
              get("/api/v1/audit/settings").session(session()).header("X-TRUST-Page-Action-Id", id))
          .andExpect(status().isNotFound());
    }
    assertEquals(2, writes.size());
    assertTrue(writes.stream().allMatch(values -> "DENIED".equals(values[5])));
  }

  @Test
  void proxiedCollectionIsDeliveredByTheActualWorkerOverLoopbackHttp() throws Exception {
    operations.enqueue(
        "TRUST_ORG", "real-user", "READ", "", "SUCCESS", "REQUEST", "", "GET", "/events", "", "");
    Object[] values = writes.get(0);
    String[] columns = {
      "id",
      "org_id",
      "actor",
      "action",
      "object_id",
      "outcome",
      "source_kind",
      "menu_id",
      "method",
      "path",
      "summary",
      "trace_id",
      "occurred_at"
    };
    var row = new HashMap<String, Object>();
    for (int i = 0; i < columns.length; i++) row.put(columns[i], values[i]);
    row.put("attempts", 0);
    when(db.queryForList(startsWith("SELECT * FROM audit_delivery"))).thenReturn(List.of(row));
    var canonical = new AtomicReference<String>();
    var completed = new AtomicBoolean();
    doAnswer(
            call -> {
              String sql = call.getArgument(0);
              Object[] arguments = (Object[]) call.getRawArguments()[1];
              if (sql.contains("SET event_json=?")) canonical.set((String) arguments[0]);
              if (sql.contains("state='DELIVERED'")) completed.set(true);
              return 1;
            })
        .when(db)
        .update(startsWith("UPDATE audit_delivery"), any(Object[].class));
    when(db.queryForObject(startsWith("SELECT event_json"), eq(String.class), anyString()))
        .thenAnswer(call -> canonical.get());
    when(db.queryForObject(startsWith("SELECT COUNT(*) FROM audit_delivery"), eq(Long.class), anyString(), anyString()))
        .thenReturn(1L);
    Path token = root.resolve("synthetic-token.txt");
    Files.writeString(token, "synthetic-local-audit-delivery-credential");
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var received = new AtomicReference<String>();
    server.createContext(
        "/events",
        exchange -> {
          String body =
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8);
          received.set(body);
          byte[] receipt =
              Json.bytes(
                  Map.of(
                      "eventId",
                      Json.map(body).get("eventId"),
                      "appCode",
                      "TRUST",
                      "stored",
                      true,
                      "payloadHash",
                      LogInput.hash(body)));
          exchange.sendResponseHeaders(202, receipt.length);
          exchange.getResponseBody().write(receipt);
          exchange.close();
        });
    server.start();
    try {
      when(settings.read())
          .thenReturn(
              new LogSettings.Config(
                  1,
                  true,
                  "",
                  "",
                  Set.of(),
                  List.of(),
                  new LogSettings.Delivery(
                      true,
                      "http://127.0.0.1:" + server.getAddress().getPort() + "/events",
                      token.toString(),
                      Map.of(
                          "TRUST_ORG",
                          new LogSettings.Scope("TEST_DOMAIN", "TEST_ZONE", "TRUST")))));
      delivery.poll();
      assertTrue(completed.get());
      assertNotNull(received.get());
      assertEquals("AUTHENTICATED", Json.map(received.get()).get("actorTrust"));
      assertEquals("real-user", Json.map(received.get()).get("userId"));
    } finally {
      server.stop(0);
    }
  }
}
