package com.bproject.trust.evidence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bproject.trust.ports.EvidenceStorage;
import com.bproject.trust.support.TestDatabase;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

/** Real filesystem fsync and restricted-role SQL. No IPFS or ledger writes. */
@EnabledIfEnvironmentVariable(named = "TRUST_RETENTION_DB_TEST", matches = "1")
class EvidenceRetentionDatabaseTest {
  @TempDir Path root;
  JdbcTemplate db;
  byte[] bytes = "%PDF-1.4 retained original".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  @BeforeEach
  void setup() {
    db = new JdbcTemplate(TestDatabase.dataSource(false));
  }

  MockMultipartFile file() {
    return new MockMultipartFile("file", "proof.pdf", "application/pdf", bytes);
  }

  EvidenceService service(JdbcTemplate jdbc) throws Exception {
    return new EvidenceService(jdbc, mock(EvidenceStorage.class), root.toString());
  }

  @Test
  @Timeout(90)
  void concurrentDiskBackedBoundaryUploadsKeepOriginalsAndRegisterOnce() throws Exception {
    Path input = root.resolve("boundary.pdf");
    try (var disk = new java.io.RandomAccessFile(input.toFile(), "rw")) {
      disk.write("%PDF-1.4\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      disk.setLength(20L * 1024 * 1024);
    }
    var file = mock(org.springframework.web.multipart.MultipartFile.class);
    when(file.getSize()).thenReturn(Files.size(input));
    when(file.getOriginalFilename()).thenReturn("boundary.pdf");
    when(file.getBytes()).thenAnswer(invocation -> {
      try (var stream = new java.io.FileInputStream(input.toFile())) {
        return stream.readAllBytes();
      }
    });
    String expectedSha = com.bproject.trust.shared.json.Json.sha(Files.readAllBytes(input));
    var receiver = service(db);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
    var start = new java.util.concurrent.CountDownLatch(1);
    var jobs = new ArrayList<java.util.concurrent.Future<Map<String, Object>>>();
    try {
      for (int i = 0; i < 16; i++) jobs.add(pool.submit(() -> {
        start.await();
        return receiver.upload(file, "RETENTION", "receiver");
      }));
      start.countDown();
      var ids = new HashSet<String>();
      for (var job : jobs) {
        var receipt = job.get(60, java.util.concurrent.TimeUnit.SECONDS);
        String id = (String) receipt.get("id");
        assertTrue(ids.add(id));
        assertEquals(20L * 1024 * 1024, ((Number) receipt.get("size_bytes")).longValue());
        assertEquals(expectedSha, receipt.get("sha256"));
        assertEquals(expectedSha, com.bproject.trust.shared.json.Json.sha(
            Files.readAllBytes(root.resolve("runtime/staging").resolve(id))));
        assertEquals(1, db.queryForObject(
            "SELECT count(*) FROM evidence WHERE id=?", Integer.class, id));
      }
      assertEquals(16, ids.size());
      assertTrue(receiver.reconcile(false).stream()
          .allMatch(receipt -> "REGISTERED".equals(receipt.get("status"))));
      var oversized = mock(org.springframework.web.multipart.MultipartFile.class);
      when(oversized.getSize()).thenReturn(20L * 1024 * 1024 + 1);
      assertThrows(com.bproject.trust.shared.web.ApiError.class,
          () -> receiver.upload(oversized, "RETENTION", "receiver"));
      verify(oversized, never()).getBytes();
    } finally {
      start.countDown();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  String receivedId() throws Exception {
    try (var files = Files.list(root.resolve("runtime/staging"))) {
      return files
          .filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}"))
          .findFirst()
          .orElseThrow()
          .getFileName()
          .toString();
    }
  }

  @Test
  void uncertainCommitPreservesOriginalAndRestartReconcilesIdempotently() throws Exception {
    var broken =
        new JdbcTemplate(TestDatabase.dataSource(false)) {
          @Override
          public int update(String sql, Object... args) {
            int n = super.update(sql, args);
            if (sql.startsWith("INSERT INTO evidence"))
              throw new org.springframework.dao.DataAccessResourceFailureException(
                  "injected lost commit receipt");
            return n;
          }
        };
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> service(broken).upload(file(), "RETENTION", "receiver"));
    String id = receivedId();
    assertArrayEquals(bytes, Files.readAllBytes(root.resolve("runtime/staging").resolve(id)));
    var restarted = service(db);
    assertEquals("REGISTERED", restarted.reconcile(true).get(0).get("status"));
    assertEquals("REGISTERED", restarted.reconcile(true).get(0).get("status"));
    assertEquals(
        1, db.queryForObject("SELECT count(*) FROM evidence WHERE id=?", Integer.class, id));
    assertEquals(
        1,
        db.queryForObject("SELECT count(*) FROM evidence_versions WHERE id=?", Integer.class, id));
  }

  @Test
  void failedRegistrationRetainsFileAndCanBeRegisteredAfterRestart() throws Exception {
    var broken =
        new JdbcTemplate(TestDatabase.dataSource(false)) {
          @Override
          public int update(String sql, Object... args) {
            if (sql.startsWith("INSERT INTO evidence"))
              throw new org.springframework.dao.DataAccessResourceFailureException(
                  "injected DB outage");
            return super.update(sql, args);
          }
        };
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> service(broken).upload(file(), "RETENTION", "receiver"));
    String id = receivedId();
    var restarted = service(db);
    assertEquals("PENDING_REGISTRATION", restarted.reconcile(false).get(0).get("status"));
    assertEquals("RECONCILED", restarted.reconcile(true).get(0).get("status"));
    assertEquals("REGISTERED", restarted.reconcile(true).get(0).get("status"));
    assertArrayEquals(bytes, Files.readAllBytes(root.resolve("runtime/staging").resolve(id)));
  }

  @Test
  void finalFileSurvivesDirectorySyncFailureAndCorruptionRequiresReview() throws Exception {
    var broken =
        new EvidenceService(db, mock(EvidenceStorage.class), root.toString()) {
          int count;

          @Override
          void syncDirectory() throws Exception {
            super.syncDirectory();
            if (++count == 2)
              throw new java.io.IOException("injected directory confirmation failure");
          }
        };
    assertThrows(java.io.IOException.class, () -> broken.upload(file(), "RETENTION", "receiver"));
    String id = receivedId();
    Path original = root.resolve("runtime/staging").resolve(id);
    assertArrayEquals(bytes, Files.readAllBytes(original));
    Files.write(original, "%PDF-1.4 different content".getBytes());
    assertEquals("REQUIRES_REVIEW", service(db).reconcile(true).get(0).get("status"));
    assertTrue(Files.isRegularFile(original));
    assertEquals(
        0, db.queryForObject("SELECT count(*) FROM evidence WHERE id=?", Integer.class, id));
  }

  @Test
  void successfulRegistrationDoesNotDependOnAnotherRead() throws Exception {
    var inserted = new AtomicBoolean();
    var fragile =
        new JdbcTemplate(TestDatabase.dataSource(false)) {
          @Override
          public int update(String sql, Object... args) {
            int n = super.update(sql, args);
            if (sql.startsWith("INSERT INTO evidence")) inserted.set(true);
            return n;
          }

          @Override
          public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (inserted.get())
              throw new org.springframework.dao.DataAccessResourceFailureException(
                  "injected post-commit read outage");
            return super.queryForList(sql, args);
          }
        };
    var receipt = service(fragile).upload(file(), "RETENTION", "receiver");
    assertTrue(inserted.get());
    assertEquals(receivedId(), receipt.get("id"));
    assertEquals("PENDING", receipt.get("storage_state"));
  }
}
