package com.bproject.trust.ingress;

import static org.mockito.Mockito.*;

import com.bproject.trust.archiving.*;
import com.bproject.trust.audit.AuditService;
import com.bproject.trust.events.*;
import com.bproject.trust.evidence.EvidenceService;
import com.bproject.trust.ports.*;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.support.TestDatabase;
import com.bproject.trust.wallet.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import javax.security.auth.x500.X500Principal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Separate-JVM crash fixture. All external components are local durable test doubles. */
public final class GenericProcessFixture {
  public static void main(String[] args) throws Exception {
    if (!"1".equals(System.getenv("TRUST_GENERIC_DB_TEST")))
      throw new IllegalStateException("Explicit test gate required");
    Path directory = Path.of(args[1]);
    var source =
        Json.MAPPER.readValue(
            Files.readString(directory.resolve("source.json")),
            EvidenceIngressSettings.Source.class);
    var ds = TestDatabase.dataSource(false);
    var db = new JdbcTemplate(ds);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    var audit = new AuditService(db);
    var events = new EventService(db, tx, audit, new EventRelations(db));
    var service = new GenericEventService(db, tx, audit);
    var storage = mock(EvidenceStorage.class);
    when(storage.add(any()))
        .thenAnswer(
            call -> {
              byte[] raw = call.getArgument(0);
              String cid = "baf" + Json.sha(raw);
              Files.write(directory.resolve(cid), raw);
              return cid;
            });
    when(storage.cat(anyString()))
        .thenAnswer(
            call -> Files.readAllBytes(directory.resolve(call.getArgument(0, String.class))));
    var ledger = mock(LedgerGateway.class);
    Path ledgerFile = directory.resolve("ledger.json");
    when(ledger.find(anyString(), anyString()))
        .thenAnswer(
            call -> Files.exists(ledgerFile) ? Json.map(Files.readString(ledgerFile)) : null);
    when(ledger.submit(anyMap(), any()))
        .thenAnswer(
            call -> {
              if (Files.exists(ledgerFile))
                throw new AssertionError("Recovered committed event was submitted twice");
              var result = new TreeMap<>(call.<Map<String, Object>>getArgument(0));
              String tid = UUID.randomUUID().toString();
              call.<Consumer<String>>getArgument(1).accept(tid);
              result.put("txId", tid);
              result.put("ledgerIdentity", "durable-process-fixture");
              result.put("blockNumber", 11);
              Files.writeString(ledgerFile, Json.write(result));
              // No shutdown hook/finally: leave the real SQL task RUNNING with a
              // prepared attempt.
              Runtime.getRuntime().halt(23);
              return result;
            });
    var keys = mock(WalletKeyStore.class);
    var cert = mock(X509Certificate.class);
    when(cert.getSubjectX500Principal())
        .thenReturn(new X500Principal("CN=synthetic-process-fixture"));
    when(cert.getNotAfter()).thenReturn(Date.from(Instant.now().plusSeconds(86400)));
    when(keys.load(anyString()))
        .thenAnswer(
            call ->
                new WalletKeyStore.Material(
                    cert,
                    null,
                    "SYNTHETIC PROCESS CERT",
                    Json.sha(call.getArgument(0, String.class).getBytes(StandardCharsets.UTF_8))));
    var wallets = new WalletService(db, tx, keys, audit);
    var settings = mock(EvidenceIngressSettings.class);
    when(settings.permits(anyMap())).thenReturn(true);
    String id;
    if (args[0].equals("crash")) {
      String registration =
          (String)
              wallets
                  .propose(
                      source.orgId(),
                      "iam:proposer",
                      "REGISTER",
                      new WalletService.Change(
                          "fixture", "Org1MSP", "key-" + UUID.randomUUID(), null, null, null),
                      "process fixture")
                  .get("id");
      wallets.review(source.orgId(), "iam:reviewer", registration, true);
      String binding =
          (String)
              wallets
                  .propose(
                      source.orgId(),
                      "iam:proposer",
                      "BIND",
                      new WalletService.Change(
                          null, null, null, registration, source.sourceSystem(), 0),
                      "process fixture")
                  .get("id");
      wallets.review(source.orgId(), "iam:reviewer", binding, true);
      var input = GenericEventInput.parse(Files.readAllBytes(directory.resolve("request.json")));
      var accepted = service.submit(source, input);
      if (accepted.status() != 202) throw new AssertionError("Expected durable acceptance");
      id = (String) accepted.receipt().get("id");
      Files.writeString(directory.resolve("accepted.json"), Json.write(accepted.receipt()));
    } else {
      id = (String) Json.map(Files.readString(directory.resolve("accepted.json"))).get("id");
    }
    Files.writeString(
        directory.resolve(args[0] + "-pid.txt"), Long.toString(ProcessHandle.current().pid()));
    var archive = new GenericEvidenceArchive(db, storage);
    var worker =
        new ArchiveWorker(
            db,
            events,
            mock(EvidenceService.class),
            storage,
            ledger,
            tx,
            wallets,
            settings,
            archive);
    FixtureArchiveRunner.run(worker, db, id, source.orgId());
    Files.writeString(directory.resolve("recovered.json"), Json.write(service.receipt(source, id)));
    if (!Arrays.equals(
        Files.readAllBytes(directory.resolve("request.json")),
        archive.verify(events.get(id, source.orgId()))))
      throw new AssertionError("Original bytes changed across process restart");
  }
}
