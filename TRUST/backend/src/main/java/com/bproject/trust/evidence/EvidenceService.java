package com.bproject.trust.evidence;

import com.bproject.trust.ports.EvidenceStorage;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class EvidenceService {
  private final JdbcTemplate db;
  private final Path staging;
  private final EvidenceStorage ipfs;

  public EvidenceService(JdbcTemplate db, EvidenceStorage ipfs, @Value("${trust.root}") String root)
      throws Exception {
    this.db = db;
    this.ipfs = ipfs;
    staging = Path.of(root, "runtime/staging");
    Files.createDirectories(staging);
  }

  private final java.util.concurrent.locks.ReentrantLock uploadLock =
      new java.util.concurrent.locks.ReentrantLock(true);

  public Map<String, Object> upload(MultipartFile file, String org, String actor) throws Exception {
    if (file.isEmpty() || file.getSize() > 20L * 1024 * 1024)
      throw new ApiError(413, "文件为空或超过 20 MiB");
    byte[] bytes = file.getBytes();
    if (bytes.length > 20L * 1024 * 1024) throw new ApiError(413, "文件超过 20 MiB");
    String mime = detect(bytes);
    String filename =
        Objects.requireNonNullElse(file.getOriginalFilename(), "evidence").replace('\\', '/');
    filename = filename.substring(filename.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
    if (filename.isBlank() || filename.length() > 240) throw new ApiError(400, "文件名无效");
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    if (!uploadLock.tryLock(30, java.util.concurrent.TimeUnit.SECONDS))
      throw new ApiError(503, "接收队列繁忙，请稍后重试");
    try (var mutex =
        com.bproject.trust.shared.DatabaseMutex.acquire(db, "staging:primary", deadline)) {
      long retained =
          db.queryForObject("SELECT COALESCE(SUM(size_bytes),0) FROM evidence", Long.class);
      var registered =
          new java.util.HashSet<>(db.queryForList("SELECT id FROM evidence", String.class));
      try (var paths = Files.newDirectoryStream(staging)) {
        for (var path : paths) {
          String name = path.getFileName().toString();
          if ((name.matches("[a-f0-9-]{36}") && !registered.contains(name))
              || name.endsWith(".part")) {
            if (Files.isSymbolicLink(path)) throw new ApiError(503, "暂存目录需要人工核查");
            retained = Math.addExact(retained, Files.size(path));
          }
        }
      }
      if (retained + bytes.length > 10L * 1024 * 1024 * 1024
          || Files.getFileStore(staging).getUsableSpace() < bytes.length + 512L * 1024 * 1024)
        throw new ApiError(503, "证据暂存容量不足，文件未确认接收");
      String id = UUID.randomUUID().toString();
      var receipt = new java.util.LinkedHashMap<String, Object>();
      receipt.put("id", id);
      receipt.put("org_id", org);
      receipt.put("uploaded_by", actor);
      receipt.put("filename", filename);
      receipt.put("mime", mime);
      receipt.put("size_bytes", bytes.length);
      receipt.put("sha256", Json.sha(bytes));
      receipt.put("created_at", java.time.Instant.now().toString());
      Path temp = staging.resolve(id + ".part"), dest = staging.resolve(id);
      Path manifestTemp = staging.resolve(id + ".receipt.part"),
          manifest = staging.resolve(id + ".receipt.json");
      try {
        durableWrite(
            manifestTemp, Json.write(receipt).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.move(manifestTemp, manifest, StandardCopyOption.ATOMIC_MOVE);
        syncDirectory();
        durableWrite(temp, bytes);
        Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE);
        syncDirectory();
        register(receipt);
        receipt.put("cid", null);
        receipt.put("storage_state", "PENDING");
        return receipt;
      } catch (Exception e) {
        // A final file may already be committed or its DB outcome may be unknown. Never remove it.
        try {
          Files.deleteIfExists(temp);
          Files.deleteIfExists(manifestTemp);
        } catch (Exception cleanup) {
          e.addSuppressed(cleanup);
        }
        throw e;
      }
    } finally {
      uploadLock.unlock();
    }
  }

  private static void durableWrite(Path path, byte[] bytes) throws Exception {
    try (var channel =
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      var buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer);
      channel.force(true);
    }
  }

  void syncDirectory() throws Exception {
    try (var channel = FileChannel.open(staging, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  private void register(Map<String, Object> receipt) {
    db.update(
        "INSERT INTO evidence(id,org_id,uploaded_by,filename,mime,size_bytes,sha256,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        receipt.get("id"),
        receipt.get("org_id"),
        receipt.get("uploaded_by"),
        receipt.get("filename"),
        receipt.get("mime"),
        receipt.get("size_bytes"),
        receipt.get("sha256"),
        java.sql.Timestamp.from(java.time.Instant.parse((String) receipt.get("created_at"))));
  }

  /** Operator-only reconciliation. Scan is the default; apply uses the original durable ID. */
  public List<Map<String, Object>> reconcile(boolean apply) throws Exception {
    var results = new java.util.ArrayList<Map<String, Object>>();
    try (var paths = Files.newDirectoryStream(staging, "*.receipt.json")) {
      for (var manifest : paths) {
        String name = manifest.getFileName().toString(), id = name.substring(0, name.length() - 13);
        try {
          if (!id.matches("[a-f0-9-]{36}")
              || Files.isSymbolicLink(manifest)
              || Files.size(manifest) > 16384)
            throw new IllegalArgumentException("INVALID_MANIFEST");
          var receipt = Json.map(Files.readString(manifest));
          Path original = staging.resolve(id);
          if (!id.equals(receipt.get("id"))
              || Files.isSymbolicLink(original)
              || !Files.isRegularFile(original)
              || Files.size(original) > 20L * 1024 * 1024
              || Files.size(original) != ((Number) receipt.get("size_bytes")).longValue())
            throw new IllegalArgumentException("MISSING_OR_INVALID_ORIGINAL");
          byte[] bytes = Files.readAllBytes(original);
          if (!Json.sha(bytes).equals(receipt.get("sha256"))
              || !detect(bytes).equals(receipt.get("mime")))
            throw new IllegalArgumentException("ORIGINAL_MISMATCH");
          var rows = db.queryForList("SELECT * FROM evidence WHERE id=?", id);
          String status = "REGISTERED";
          if (rows.isEmpty()) {
            status = "PENDING_REGISTRATION";
            if (apply) {
              try {
                register(receipt);
              } catch (org.springframework.dao.DuplicateKeyException concurrent) {
                /* Verify the winner below. */
              }
              rows = db.queryForList("SELECT * FROM evidence WHERE id=?", id);
              if (rows.isEmpty()) throw new IllegalStateException("REGISTRATION_UNCONFIRMED");
              status = "RECONCILED";
            }
          }
          if (!rows.isEmpty())
            for (String field :
                List.of("id", "org_id", "uploaded_by", "filename", "mime", "size_bytes", "sha256"))
              if (!Objects.toString(rows.get(0).get(field))
                  .equals(Objects.toString(receipt.get(field))))
                throw new IllegalStateException("REGISTERED_METADATA_MISMATCH");
          results.add(Map.of("id", id, "status", status));
        } catch (Exception failure) {
          results.add(
              Map.of(
                  "id",
                  id,
                  "status",
                  "REQUIRES_REVIEW",
                  "reason",
                  failure.getClass().getSimpleName()));
        }
      }
    }
    return results;
  }

  static String detect(byte[] b) {
    if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-')
      return "application/pdf";
    if (b.length >= 8
        && Arrays.equals(Arrays.copyOf(b, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
      return "image/png";
    if (b.length >= 3 && b[0] == (byte) 255 && b[1] == (byte) 216 && b[2] == (byte) 255)
      return "image/jpeg";
    throw new ApiError(415, "仅接收具有 PDF、PNG 或 JPEG 文件标识的证据");
  }

  public Map<String, Object> get(String id, String org) {
    return db.queryForList("SELECT * FROM evidence_read WHERE id=? AND org_id=?", id, org).stream()
        .findFirst()
        .orElseThrow(() -> new ApiError(404, "证据不存在或无权访问"));
  }

  @SuppressWarnings("unchecked")
  public static void assertBindings(String canonical, List<Map<String, Object>> actual) {
    var expected = (List<Map<String, Object>>) Json.map(canonical).get("evidence");
    if (expected.size() != actual.size()) throw new ApiError(409, "证据引用数量与原始事件不一致");
    for (var original : expected) {
      var current =
          actual.stream()
              .filter(e -> e.get("id").equals(original.get("id")))
              .findFirst()
              .orElseThrow(() -> new ApiError(409, "证据引用已被替换"));
      for (String field : List.of("filename", "mime", "size_bytes", "sha256"))
        if (!Objects.toString(original.get(field)).equals(Objects.toString(current.get(field))))
          throw new ApiError(409, "证据索引与原始事件不一致：" + field);
    }
  }

  public byte[] storedBytes(Map<String, Object> e) throws Exception {
    byte[] bytes = ipfs.cat((String) e.get("cid"));
    if (bytes.length != ((Number) e.get("size_bytes")).longValue()
        || !Json.sha(bytes).equals(e.get("sha256"))) throw new ApiError(409, "证据摘要不一致");
    return bytes;
  }

  public Map<String, Object> archive(String id, String org) throws Exception {
    var e = get(id, org);
    if (e.get("cid") != null) {
      storedBytes(e);
      return e;
    }
    byte[] bytes = Files.readAllBytes(staging.resolve(id));
    if (bytes.length != ((Number) e.get("size_bytes")).longValue()
        || !Json.sha(bytes).equals(e.get("sha256"))) throw new ApiError(409, "暂存证据摘要不一致，需人工核查");
    String cid = ipfs.add(bytes);
    db.update(
        "UPDATE evidence_read SET cid=?,storage_state='STORED' WHERE id=? AND cid IS NULL",
        cid,
        id);
    return get(id, org);
  }
}
