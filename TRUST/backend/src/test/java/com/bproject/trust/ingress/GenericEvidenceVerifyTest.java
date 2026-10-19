package com.bproject.trust.ingress;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bproject.trust.archiving.AttestationRecord;
import com.bproject.trust.config.IdentityInstance;
import com.bproject.trust.ports.EvidenceStorage;
import com.bproject.trust.shared.json.Json;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class GenericEvidenceVerifyTest {
  @TempDir Path directory;
  Path root, allowFile, planFile, credentialFile;
  GenericEvidenceVerify.Target target;
  GenericEvidenceVerify.Plan plan;
  JdbcTemplate db;
  EvidenceStorage storage;
  Map<String, Object> row, data, ledger;
  byte[] original;
  final AtomicInteger opened = new AtomicInteger(),
      closed = new AtomicInteger(),
      queries = new AtomicInteger();
  String stdout;

  @BeforeEach
  void setup() throws Exception {
    root = Files.createDirectories(directory.resolve("TRUST"));
    Files.createDirectories(root.resolve(".local"));
    target =
        new GenericEvidenceVerify.Target(
            true,
            "dedicated-generic-verification",
            new IdentityInstance(
                root.toString(),
                "jdbc:opengauss://127.0.0.1:25432/trust_iam_verify_test",
                "trust_iam_verify_app",
                "trust_iam_verify_migrate",
                "trust-iam-verify",
                28982,
                "query-example"),
            "http://127.0.0.1:29501",
            "127.0.0.1:29551",
            "peer.example.invalid",
            "evidence",
            "ExampleMSP",
            "a".repeat(64));
    plan =
        new GenericEvidenceVerify.Plan(
            1,
            "verification-example",
            target,
            "ORG",
            "SOURCE",
            "operator-source",
            "00000000-0000-0000-0000-000000000001");
    allowFile = root.resolve(".local/allow.json");
    planFile = root.resolve(".local/plan.json");
    credentialFile = root.resolve(".local/credentials.json");
    Files.writeString(
        allowFile,
        Json.write(new GenericEvidenceVerify.Allowlist(1, Map.of(plan.instanceId(), target))));
    Files.writeString(planFile, Json.write(plan));
    Files.writeString(
        credentialFile,
        Json.write(new GenericEvidenceVerify.Credentials("synthetic-not-a-password")));
    original =
        GenericEventInputTest.body("{\"confidential\":\"OFFLINE_SYNTHETIC_RAW_MARKER\"}")
            .getBytes(StandardCharsets.UTF_8);
    var input = GenericEventInput.parse(original);
    var canonical = new TreeMap<String, Object>();
    canonical.put("schemaVersion", 2);
    canonical.put("recordKind", "GENERIC_V1");
    canonical.put("id", plan.eventId());
    canonical.put("orgId", plan.orgId());
    canonical.put("submittedBy", "source:" + plan.sourceId());
    canonical.put("rootId", plan.eventId());
    canonical.put("version", 1);
    canonical.put("supersedesId", null);
    canonical.put("event", input.event(plan.sourceSystem()));
    canonical.put("evidence", List.of());
    canonical.put(
        "originalRequest",
        Map.of(
            "sha256",
            Json.sha(original),
            "sizeBytes",
            original.length,
            "mediaType",
            "application/json"));
    row = new HashMap<>();
    row.put("id", plan.eventId());
    row.put("org_id", plan.orgId());
    row.put("source_system", plan.sourceSystem());
    row.put("submitted_by", "source:" + plan.sourceId());
    row.put("record_kind", "GENERIC_V1");
    row.put("root_id", plan.eventId());
    row.put("version", 1);
    row.put("supersedes_id", null);
    row.put("batch_id", null);
    row.put("source_event_id", input.sourceEventId());
    row.put("event_type", input.eventType());
    row.put("object_id", input.businessObjectId());
    row.put("occurred_at", Timestamp.from(input.occurredAt()));
    row.put("signing_required", true);
    row.put("task_state", "DONE");
    row.put("chain_state", "COMMITTED");
    row.put("file_state", "STORED");
    row.put("wallet_id", "synthetic-wallet");
    row.put("signer_fingerprint", "b".repeat(64));
    row.put("canonical_json", Json.write(canonical));
    row.put("event_sha256", Json.sha(Json.bytes(canonical)));
    row.put("request_json", input.request(plan.sourceSystem()));
    data =
        new HashMap<>(
            Map.of(
                "original_utf8",
                new String(original, StandardCharsets.UTF_8),
                "original_sha256",
                Json.sha(original),
                "original_size_bytes",
                original.length,
                "original_cid",
                "baf" + "a".repeat(30)));
    String manifest =
        Json.write(
            Map.of(
                "schemaVersion",
                2,
                "event",
                canonical,
                "eventSha256",
                row.get("event_sha256"),
                "evidence",
                List.of(),
                "originalRequest",
                Map.of(
                    "cid",
                    data.get("original_cid"),
                    "sha256",
                    data.get("original_sha256"),
                    "sizeBytes",
                    original.length,
                    "mediaType",
                    "application/json")));
    row.put("manifest_json", manifest);
    row.put("manifest_sha256", Json.sha(manifest.getBytes(StandardCharsets.UTF_8)));
    row.put("manifest_cid", "baf" + "c".repeat(30));
    ledger = new HashMap<>(AttestationRecord.record(row));
    db = mock(JdbcTemplate.class);
    storage = mock(EvidenceStorage.class);
    when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));
    when(db.queryForMap(anyString(), any(Object[].class))).thenReturn(data);
    when(db.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    when(storage.cat((String) data.get("original_cid"))).thenReturn(original);
    when(storage.cat((String) row.get("manifest_cid")))
        .thenReturn(manifest.getBytes(StandardCharsets.UTF_8));
  }

  GenericEvidenceVerify.Resources resources() {
    return new GenericEvidenceVerify.Resources() {
      public JdbcTemplate db() {
        return db;
      }

      public EvidenceStorage storage() {
        return storage;
      }

      public Map<String, Object> find(String org, String id) {
        assertEquals(plan.orgId(), org);
        assertEquals(plan.eventId(), id);
        queries.incrementAndGet();
        return ledger;
      }

      public void close() {
        closed.incrementAndGet();
      }
    };
  }

  int run(String outputName, String... flags) {
    var args =
        new ArrayList<>(
            List.of(
                "--allowlist",
                allowFile.toString(),
                "--plan",
                planFile.toString(),
                "--credentials",
                credentialFile.toString(),
                "--output",
                root.resolve(".local/" + outputName).toString()));
    args.addAll(List.of(flags));
    var bytes = new ByteArrayOutputStream();
    int code =
        GenericEvidenceVerify.run(
            args.toArray(String[]::new),
            (approved, credentials) -> {
              opened.incrementAndGet();
              assertEquals(target, approved);
              assertEquals("synthetic-not-a-password", credentials.databasePassword());
              return resources();
            },
            new PrintStream(bytes, true, StandardCharsets.UTF_8));
    stdout = bytes.toString(StandardCharsets.UTF_8);
    return code;
  }

  @Test
  void defaultDoesNotReadCredentialOrOpenResourcesOrWriteOutput() throws Exception {
    Files.delete(credentialFile);
    assertEquals(0, run("dry"));
    assertTrue(stdout.contains("DRY_RUN"));
    assertEquals(0, opened.get());
    assertFalse(Files.exists(root.resolve(".local/dry")));
    verifyNoInteractions(db, storage);
  }

  @Test
  void successUsesExactScopeOnlyReadsAndDefaultsToSummary() throws Exception {
    assertEquals(0, run("summary", "--execute"));
    var report = Json.map(Files.readString(root.resolve(".local/summary/report.json")));
    assertEquals("MATCH", report.get("hashVerification"));
    assertEquals("MATCH", report.get("ledgerRegistration"));
    assertTrue(report.get("notProven").toString().contains("CA_OR_CRL_STATUS"));
    assertEquals(1, closed.get());
    assertEquals(1, queries.get());
    assertFalse(Files.exists(root.resolve(".local/summary/source-request.json")));
    assertFalse(stdout.contains("OFFLINE_SYNTHETIC_RAW_MARKER"));
    assertFalse(stdout.contains("synthetic-not-a-password"));
    verify(db)
        .queryForList(
            contains("e.record_kind='GENERIC_V1'"),
            eq(plan.orgId()),
            eq(plan.sourceSystem()),
            eq("source:" + plan.sourceId()),
            eq(plan.eventId()));
    assertTrue(
        mockingDetails(db).getInvocations().stream()
            .allMatch(i -> i.getMethod().getName().startsWith("query")));
    verify(storage).cat((String) data.get("original_cid"));
    verify(storage).cat((String) row.get("manifest_cid"));
    verifyNoMoreInteractions(storage);
  }

  @Test
  void explicitRawOutputPreservesExactBytesOnlyAfterVerification() throws Exception {
    assertEquals(0, run("raw", "--execute", "--save-original"));
    assertArrayEquals(original, Files.readAllBytes(root.resolve(".local/raw/source-request.json")));
    assertEquals(
        row.get("manifest_json"),
        Files.readString(root.resolve(".local/raw/evidence-manifest.json")));
  }

  @Test
  void missingIncompleteOrOutOfScopeTargetNeverFetchesOrSucceeds() {
    when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    assertEquals(1, run("missing", "--execute"));
    assertTrue(stdout.contains("TARGET_NOT_FOUND"));
    when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));
    for (String field : List.of("org_id", "source_system", "submitted_by", "id", "record_kind")) {
      Object old = row.put(field, "wrong");
      assertEquals(1, run("scope-" + field, "--execute"));
      row.put(field, old);
    }
    row.put("chain_state", "CONFIRMING");
    assertEquals(1, run("pending", "--execute"));
    assertTrue(stdout.contains("TARGET_NOT_COMPLETE"));
    assertEquals(0, queries.get());
    verifyNoInteractions(storage);
  }

  @Test
  void everyDigestMismatchFailsBeforeLedgerAndDoesNotSaveRaw() throws Exception {
    for (String field : List.of("event_sha256", "manifest_sha256")) {
      Object old = row.put(field, "0".repeat(64));
      assertEquals(1, run(field, "--execute", "--save-original"));
      assertFalse(Files.exists(root.resolve(".local/" + field + "/source-request.json")));
      row.put(field, old);
    }
    data.put("original_sha256", "0".repeat(64));
    assertEquals(1, run("original", "--execute", "--save-original"));
    assertEquals(0, queries.get());
    assertFalse(Files.exists(root.resolve(".local/original/source-request.json")));
  }

  @Test
  void ipfsRawOrManifestTamperingCannotBeReportedAsVerified() throws Exception {
    when(storage.cat((String) data.get("original_cid")))
        .thenReturn("tampered".getBytes(StandardCharsets.UTF_8));
    assertEquals(1, run("raw-mismatch", "--execute"));
    when(storage.cat((String) data.get("original_cid"))).thenReturn(original);
    when(storage.cat((String) row.get("manifest_cid")))
        .thenReturn("{}".getBytes(StandardCharsets.UTF_8));
    assertEquals(1, run("manifest-mismatch", "--execute"));
    assertEquals(0, queries.get());
  }

  @Test
  void ledgerMissingOrDifferentSeparatesHashProofAndSavesNoRaw() throws Exception {
    ledger = null;
    assertEquals(1, run("no-ledger", "--execute", "--save-original"));
    var report = Json.map(Files.readString(root.resolve(".local/no-ledger/report.json")));
    assertEquals("MATCH", report.get("hashVerification"));
    assertEquals("NOT_CHECKED", report.get("ledgerRegistration"));
    assertEquals("LEDGER_RECORD_MISSING", report.get("code"));
    ledger = new HashMap<>(AttestationRecord.record(row));
    ledger.put("sourceSystem", "another");
    assertEquals(1, run("wrong-ledger", "--execute", "--save-original"));
    assertTrue(stdout.contains("LEDGER_MISMATCH"));
    assertFalse(Files.exists(root.resolve(".local/wrong-ledger/source-request.json")));
  }

  @Test
  void planCannotAuthorizeItsOwnEndpointChannelOrQueryIdentity() throws Exception {
    String approved = Files.readString(planFile);
    for (String pair :
        List.of(
            "29501:29502",
            "29551:29552",
            "trust-iam-verify:trust-iam-another",
            "query-example:query-another",
            "a".repeat(64) + ":" + "c".repeat(64),
            "ExampleMSP:DifferentMSP",
            "peer.example.invalid:other.example.invalid",
            "evidence:other-contract")) {
      String[] values = pair.split(":");
      Files.writeString(planFile, approved.replace(values[0], values[1]));
      assertEquals(1, run("changed-" + values[0], "--execute"));
      assertTrue(stdout.contains("TARGET_NOT_APPROVED"));
    }
    assertEquals(0, opened.get());
    verifyNoInteractions(db, storage);
  }

  @Test
  void disabledAllowlistRemoteEndpointsAndLegacyInstancesFailClosed() throws Exception {
    var approved = Files.readString(allowFile);
    var proposed = Files.readString(planFile);
    for (String[] pair :
        List.of(
            new String[] {"\"enabled\":true", "\"enabled\":false"},
            new String[] {"http://127.0.0.1:29501", "http://example.invalid:29501"},
            new String[] {"127.0.0.1:29551", "127.0.0.1:28183"},
            new String[] {"trust_iam_verify_test", "project_b"},
            new String[] {"\"queryKeyRef\":\"query-example\"", "\"queryKeyRef\":\"\""})) {
      Files.writeString(allowFile, approved.replace(pair[0], pair[1]));
      Files.writeString(planFile, proposed.replace(pair[0], pair[1]));
      assertEquals(1, run("rejected", "--execute"));
    }
    assertEquals(0, opened.get());
  }

  @Test
  void existingOutputAndOutputOutsideLocalCannotOverwrite() throws Exception {
    Path old = Files.createDirectory(root.resolve(".local/existing"));
    Files.writeString(old.resolve("keep"), "preserve");
    assertEquals(1, run("existing", "--execute"));
    assertEquals("preserve", Files.readString(old.resolve("keep")));
    assertThrows(
        GenericEvidenceVerify.Failure.class,
        () -> GenericEvidenceVerify.reserveOutput(root, root.resolve("outside")));
    assertThrows(
        GenericEvidenceVerify.Failure.class,
        () -> GenericEvidenceVerify.reserveOutput(root, root.resolve(".local")));
    assertEquals(0, opened.get());
  }

  @Test
  void linksAreRefusedWhenPlatformSupportsCreatingThem() throws Exception {
    Path outside = Files.createDirectory(directory.resolve("outside"));
    Path link = root.resolve(".local/link");
    try {
      Files.createSymbolicLink(link, outside);
    } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
      Assumptions.abort("Host does not permit creating symbolic links");
    }
    assertThrows(
        GenericEvidenceVerify.Failure.class,
        () -> GenericEvidenceVerify.reserveOutput(root, link.resolve("result")));
    assertFalse(Files.exists(outside.resolve("result")));
  }

  @Test
  void changedQueryCertificateIsRefusedBeforeGatewayUse() {
    assertDoesNotThrow(() -> ReadOnlyVerificationResources.checkQueryFingerprint(target, "a".repeat(64)));
    var error = assertThrows(GenericEvidenceVerify.Failure.class,
        () -> ReadOnlyVerificationResources.checkQueryFingerprint(target, "b".repeat(64)));
    assertEquals("QUERY_IDENTITY_CHANGED", error.code);
  }

  @Test
  void snapshotIsReadOnlyRepeatableAndNeverCommits() throws Exception {
    var connection = mock(Connection.class);
    when(connection.isReadOnly()).thenReturn(true);
    when(connection.getAutoCommit()).thenReturn(false);
    when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
    ReadOnlyVerificationResources.configureSnapshot(connection);
    var sequence = inOrder(connection);
    sequence.verify(connection).setReadOnly(true);
    sequence.verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    sequence.verify(connection).setAutoCommit(false);
    verify(connection, never()).commit();
    verify(connection, never()).createStatement();
    when(connection.isReadOnly()).thenReturn(false);
    assertThrows(
        GenericEvidenceVerify.Failure.class,
        () -> ReadOnlyVerificationResources.configureSnapshot(connection));
  }

  @Test
  void readOnlyStorageRejectsMutationAndBoundsResponseWithoutNetworking() throws Exception {
    var ipfs = new ReadOnlyVerificationResources.ReadOnlyIpfs("http://127.0.0.1:29501");
    assertThrows(UnsupportedOperationException.class, () -> ipfs.add(original));
    assertThrows(UnsupportedOperationException.class, ipfs::usedBytes);
    assertThrows(GenericEvidenceVerify.Failure.class, () -> ipfs.cat("bad?cid"));
    var subscriber = new ReadOnlyVerificationResources.LimitedBody(3);
    var subscription = mock(Flow.Subscription.class);
    subscriber.onSubscribe(subscription);
    subscriber.onNext(
        List.of(ByteBuffer.wrap(new byte[] {1, 2}), ByteBuffer.wrap(new byte[] {3, 4})));
    assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    verify(subscription, atLeastOnce()).cancel();
  }
}
