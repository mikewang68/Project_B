package com.bproject.trust.verification;

import com.bproject.trust.archiving.AttestationRecord;
import com.bproject.trust.audit.AuditService;
import com.bproject.trust.events.EventService;
import com.bproject.trust.evidence.EvidenceService;
import com.bproject.trust.ports.EvidenceStorage;
import com.bproject.trust.ports.LedgerGateway;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VerificationService {
  final EventService events;
  final EvidenceService evidence;
  final EvidenceStorage ipfs;
  final LedgerGateway fabric;
  private final Path exports;
  private final AuditService audit;

  public VerificationService(
      EventService events,
      EvidenceService evidence,
      EvidenceStorage ipfs,
      LedgerGateway fabric,
      AuditService audit,
      @Value("${trust.root}") String root)
      throws IOException {
    this.events = events;
    this.evidence = evidence;
    this.ipfs = ipfs;
    this.fabric = fabric;
    this.audit = audit;
    exports = Path.of(root, "runtime/exports");
    Files.createDirectories(exports);
    // Only incomplete exports from a previous application process are disposable.
    try (var old = Files.newDirectoryStream(exports, "trust-*.zip")) {
      for (Path file : old) Files.deleteIfExists(file);
    }
  }

  public Map<String, Object> verify(String id, String org, String actor) {
    var row = events.detail(id, org);
    var checks = new ArrayList<Map<String, Object>>();
    checks.add(
        Map.of(
            "item",
            "事件原文",
            "ok",
            Json.sha(((String) row.get("canonical_json")).getBytes(StandardCharsets.UTF_8))
                .equals(row.get("event_sha256"))));
    try {
      assertProjection(row);
      checks.add(Map.of("item", "业务索引与关联", "ok", true));
    } catch (Exception e) {
      checks.add(Map.of("item", "业务索引与关联", "ok", false, "message", "数据库查询字段或关联与归档原文不一致"));
    }
    try {
      EvidenceService.assertBindings((String) row.get("canonical_json"), evidenceList(row));
      checks.add(Map.of("item", "证据引用", "ok", true));
    } catch (Exception e) {
      checks.add(Map.of("item", "证据引用", "ok", false, "message", "证据索引与原始记录不一致"));
    }
    try {
      byte[] bytes = ipfs.cat((String) row.get("manifest_cid"));
      var manifest = Json.map(new String(bytes, StandardCharsets.UTF_8));
      boolean match =
          Json.sha(bytes).equals(row.get("manifest_sha256"))
              && Json.write(manifest.get("event")).equals(row.get("canonical_json"));
      @SuppressWarnings("unchecked")
      var manifestFiles = (List<Map<String, Object>>) manifest.get("evidence");
      for (var ev : evidenceList(row))
        match =
            match
                && manifestFiles.stream()
                    .anyMatch(
                        v ->
                            Objects.equals(v.get("id"), ev.get("id"))
                                && Objects.equals(v.get("cid"), ev.get("cid"))
                                && Objects.equals(v.get("sha256"), ev.get("sha256")));
      match = match && manifestFiles.size() == evidenceList(row).size();
      checks.add(Map.of("item", "IPFS 证据清单", "ok", match));
    } catch (Exception e) {
      checks.add(Map.of("item", "IPFS 证据清单", "ok", false, "message", "不可取回或尚未保存"));
    }
    for (var ev : evidenceList(row))
      try {
        evidence.storedBytes(ev);
        checks.add(Map.of("item", ev.get("filename"), "ok", true));
      } catch (Exception e) {
        checks.add(Map.of("item", ev.get("filename"), "ok", false, "message", "证据缺失、不可取回或摘要不一致"));
      }
    Map<String, Object> ledger = null;
    try {
      ledger = fabric.find(org, id);
      if (ledger == null) throw new IllegalStateException();
      AttestationRecord.assertMatches(AttestationRecord.record(row), ledger);
      checks.add(Map.of("item", "Fabric 登记", "ok", true));
    } catch (Exception e) {
      checks.add(Map.of("item", "Fabric 登记", "ok", false, "message", "未登记、暂不可查或内容不一致"));
    }
    audit.record(org, actor, "VERIFY", id, null);
    var result = new LinkedHashMap<String, Object>();
    result.put("ok", checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("ok"))));
    result.put("checks", checks);
    result.put("ledger", ledger);
    result.put("meaning", "检查归档记录和文件的一致性，不证明实物或原始业务事实真实。");
    return result;
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> evidenceList(Map<String, Object> row) {
    return (List<Map<String, Object>>) row.get("evidence");
  }

  @SuppressWarnings("unchecked")
  static void assertProjection(Map<String, Object> row) {
    var original = Json.map((String) row.get("canonical_json"));
    var input = (Map<String, Object>) original.get("event");
    var names =
        Map.of(
            "id",
            "id",
            "org_id",
            "orgId",
            "submitted_by",
            "submittedBy",
            "root_id",
            "rootId",
            "version",
            "version",
            "supersedes_id",
            "supersedesId");
    for (var name : names.entrySet())
      if (!Objects.toString(row.get(name.getKey()), "")
          .equals(Objects.toString(original.get(name.getValue()), "")))
        throw new IllegalStateException();
    var fields =
        Map.of(
            "source_system",
            "sourceSystem",
            "source_event_id",
            "sourceEventId",
            "event_type",
            "eventType",
            "batch_id",
            "batchId",
            "object_id",
            "businessObjectId");
    for (var name : fields.entrySet())
      if (!Objects.equals(row.get(name.getKey()), input.get(name.getValue())))
        throw new IllegalStateException();
    if (!((Timestamp) row.get("occurred_at"))
        .toInstant()
        .equals(
            Instant.parse((String) input.get("occurredAt"))
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS)))
      throw new IllegalStateException();
    Set<String> expected = new HashSet<>();
    expected.add("BATCH\n" + input.get("batchId"));
    for (var field :
        Map.of("relatedBatchIds", "BATCH", "bundleIds", "BUNDLE", "relatedEventRefs", "EVENT")
            .entrySet())
      for (Object value : (List<?>) input.get(field.getKey()))
        expected.add(field.getValue() + "\n" + value);
    if (input.get("handoverId") instanceof String value && !value.isBlank())
      expected.add("HANDOVER\n" + value);
    Set<String> actual = new HashSet<>();
    for (var link : (List<Map<String, Object>>) row.get("links"))
      actual.add(link.get("kind") + "\n" + link.get("target"));
    if (!actual.equals(expected)) throw new IllegalStateException();
  }

  public Path export(String id, String org, String actor) throws Exception {
    var row = events.detail(id, org);
    var checked = verify(id, org, actor);
    if (!Boolean.TRUE.equals(checked.get("ok")))
      throw new ApiError(409, "核验未通过，不能导出完整证据包，请先检查核验结果");
    long required =
        evidenceList(row).stream().mapToLong(e -> ((Number) e.get("size_bytes")).longValue()).sum()
            + 64L * 1024 * 1024;
    if (Files.getFileStore(exports).getUsableSpace() < required)
      throw new ApiError(503, "导出临时空间不足，请稍后重试");
    Path file = Files.createTempFile(exports, "trust-", ".zip");
    boolean completed = false;
    try {
      var hashes = new TreeMap<String, String>();
      try (var zip = new ZipOutputStream(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
        entry(
            zip,
            hashes,
            "event.json",
            ((String) row.get("canonical_json")).getBytes(StandardCharsets.UTF_8));
        entry(zip, hashes, "evidence-manifest.json", ipfs.cat((String) row.get("manifest_cid")));
        entry(zip, hashes, "ledger-query.json", Json.bytes(checked.get("ledger")));
        // At most one evidence file is in memory; the ZIP itself stays on disk.
        for (var ev : evidenceList(row))
          entry(
              zip,
              hashes,
              "evidence/" + ev.get("id") + extension((String) ev.get("mime")),
              evidence.storedBytes(ev));
        entry(zip, null, "checksums.json", Json.bytes(hashes));
        entry(
            zip,
            null,
            "README.txt",
            ("B项目证据包\n清单 CID："
                    + row.get("manifest_cid")
                    + "\n"
                    + "离线校验只验证包内文件一致性，清单本身需与可信渠道取得的 CID/摘要比较。ledger-query.json"
                    + " 是在线查询记录，不是独立链上密码学证明。\n")
                .getBytes(StandardCharsets.UTF_8));
      }
      audit.record(org, actor, "EXPORT", id, null);
      completed = true;
      return file;
    } finally {
      if (!completed) Files.deleteIfExists(file);
    }
  }

  private static void entry(
      ZipOutputStream zip, Map<String, String> hashes, String name, byte[] bytes)
      throws IOException {
    var item = new ZipEntry(name);
    item.setTime(0);
    zip.putNextEntry(item);
    zip.write(bytes);
    zip.closeEntry();
    if (hashes != null) hashes.put(name, Json.sha(bytes));
  }

  static String extension(String mime) {
    return switch (mime) {
      case "application/pdf" -> ".pdf";
      case "image/png" -> ".png";
      default -> ".jpg";
    };
  }
}
