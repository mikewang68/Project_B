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

  public synchronized Map<String, Object> upload(MultipartFile file, String org, String actor)
      throws Exception {
    if (file.isEmpty() || file.getSize() > 20L * 1024 * 1024)
      throw new ApiError(413, "文件为空或超过 20 MiB");
    byte[] bytes = file.getBytes();
    String mime = detect(bytes);
    String filename =
        Objects.requireNonNullElse(file.getOriginalFilename(), "evidence").replace('\\', '/');
    filename = filename.substring(filename.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
    if (filename.isBlank() || filename.length() > 240) throw new ApiError(400, "文件名无效");
    long retained =
        db.queryForObject("SELECT COALESCE(SUM(size_bytes),0) FROM evidence", Long.class);
    if (retained + bytes.length > 10L * 1024 * 1024 * 1024
        || Files.getFileStore(staging).getUsableSpace() < bytes.length + 512L * 1024 * 1024)
      throw new ApiError(503, "证据暂存容量不足，文件未确认接收");
    String id = UUID.randomUUID().toString();
    Path temp = staging.resolve(id + ".part"), dest = staging.resolve(id);
    try {
      try (var channel =
          FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        var buf = ByteBuffer.wrap(bytes);
        while (buf.hasRemaining()) channel.write(buf);
        channel.force(true);
      }
      Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE);
      try (var channel = FileChannel.open(staging, StandardOpenOption.READ)) {
        channel.force(true);
      }
      db.update(
          "INSERT INTO evidence(id,org_id,uploaded_by,filename,mime,size_bytes,sha256)"
              + " VALUES(?,?,?,?,?,?,?)",
          id,
          org,
          actor,
          filename,
          mime,
          bytes.length,
          Json.sha(bytes));
      return get(id, org);
    } catch (Exception e) {
      Files.deleteIfExists(temp);
      Files.deleteIfExists(dest);
      throw e;
    }
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
    return db.queryForList("SELECT * FROM evidence WHERE id=? AND org_id=?", id, org).stream()
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
    if (!Json.sha(bytes).equals(e.get("sha256"))) throw new ApiError(409, "证据摘要不一致");
    return bytes;
  }

  public synchronized Map<String, Object> archive(String id, String org) throws Exception {
    var e = get(id, org);
    if (e.get("cid") != null) {
      storedBytes(e);
      return e;
    }
    byte[] bytes = Files.readAllBytes(staging.resolve(id));
    if (!Json.sha(bytes).equals(e.get("sha256"))) throw new ApiError(409, "暂存证据摘要不一致，需人工核查");
    String cid = ipfs.add(bytes);
    db.update(
        "UPDATE evidence SET cid=?,storage_state='STORED' WHERE id=? AND cid IS NULL", cid, id);
    return get(id, org);
  }
}
