package com.bproject.trust.ingress;

import com.bproject.trust.ports.EvidenceStorage;
import com.bproject.trust.shared.json.Json;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Schema-2 handling is isolated from the historical canonical and manifest serializers. */
@Service
public class GenericEvidenceArchive {
  private final JdbcTemplate db;
  private final EvidenceStorage ipfs;

  public GenericEvidenceArchive(JdbcTemplate db, EvidenceStorage ipfs) {
    this.db = db;
    this.ipfs = ipfs;
  }

  public static boolean generic(Map<String, Object> row) {
    return "GENERIC_V1".equals(row.get("record_kind"));
  }

  private static void check(boolean ok) {
    if (!ok) throw new IngressError(409, "INTEGRITY_MISMATCH", "通用证据原文、摘要或投影不一致");
  }

  public Map<String, Object> local(Map<String, Object> row) {
    String id = (String) row.get("id");
    var data = db.queryForMap("SELECT * FROM generic_event_data_read WHERE event_id=?", id);
    assertLocal(row, data);
    check(
        db.queryForObject("SELECT COUNT(*) FROM event_links WHERE event_id=?", Long.class, id)
            == 0);
    check(
        db.queryForObject("SELECT COUNT(*) FROM event_evidence WHERE event_id=?", Long.class, id)
            == 0);
    return data;
  }

  @SuppressWarnings("unchecked")
  public static void assertLocal(Map<String, Object> row, Map<String, Object> data) {
    check(
        generic(row)
            && row.get("batch_id") == null
            && Boolean.TRUE.equals(row.get("signing_required")));
    String canonical = (String) row.get("canonical_json");
    check(Json.sha(canonical.getBytes(StandardCharsets.UTF_8)).equals(row.get("event_sha256")));
    var c = Json.map(canonical);
    check(
        c.keySet()
            .equals(
                Set.of(
                    "schemaVersion",
                    "recordKind",
                    "id",
                    "orgId",
                    "submittedBy",
                    "rootId",
                    "version",
                    "supersedesId",
                    "event",
                    "evidence",
                    "originalRequest")));
    check(
        Integer.valueOf(2).equals(c.get("schemaVersion"))
            && "GENERIC_V1".equals(c.get("recordKind")));
    for (var entry :
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
                "supersedesId")
            .entrySet()) check(Objects.equals(row.get(entry.getKey()), c.get(entry.getValue())));
    check(c.get("evidence") instanceof List<?> l && l.isEmpty());
    byte[] original = ((String) data.get("original_utf8")).getBytes(StandardCharsets.UTF_8);
    check(original.length == ((Number) data.get("original_size_bytes")).longValue());
    check(Json.sha(original).equals(data.get("original_sha256")));
    var binding = (Map<String, Object>) c.get("originalRequest");
    check(
        Json.write(binding)
            .equals(
                Json.write(
                    Map.of(
                        "sha256",
                        data.get("original_sha256"),
                        "sizeBytes",
                        original.length,
                        "mediaType",
                        "application/json"))));
    var input = GenericEventInput.parse(original);
    check(Objects.equals(input.supersedesId(), row.get("supersedes_id")));
    check(
        input.sourceEventId().equals(row.get("source_event_id"))
            && input.eventType().equals(row.get("event_type"))
            && input.businessObjectId().equals(row.get("object_id"))
            && input.occurredAt().equals(((Timestamp) row.get("occurred_at")).toInstant()));
    check(input.request((String) row.get("source_system")).equals(row.get("request_json")));
    check(
        Json.write(input.event((String) row.get("source_system")))
            .equals(Json.write(c.get("event"))));
  }

  public String manifest(Map<String, Object> row, String lease) throws Exception {
    var data = local(row);
    String id = (String) row.get("id");
    if (data.get("original_cid") == null) {
      String cid = ipfs.add(((String) data.get("original_utf8")).getBytes(StandardCharsets.UTF_8));
      if (db.update(
              "UPDATE generic_event_data_read SET expected_lease_token=?,original_cid=? WHERE"
                  + " event_id=? AND original_cid IS NULL AND EXISTS(SELECT 1 FROM tasks_read WHERE"
                  + " event_id=? AND state='RUNNING' AND lease_token=? AND"
                  + " lease_until>CURRENT_TIMESTAMP)",
              lease,
              cid,
              id,
              id,
              lease)
          == 0) return null;
      data = db.queryForMap("SELECT * FROM generic_event_data_read WHERE event_id=?", id);
    }
    originalBytes(data);
    return Json.write(
        Map.of(
            "schemaVersion",
            2,
            "event",
            Json.map((String) row.get("canonical_json")),
            "eventSha256",
            row.get("event_sha256"),
            "evidence",
            List.of(),
            "originalRequest",
            originalReference(data)));
  }

  private Map<String, Object> originalReference(Map<String, Object> data) {
    return Map.of(
        "cid",
        data.get("original_cid"),
        "sha256",
        data.get("original_sha256"),
        "sizeBytes",
        data.get("original_size_bytes"),
        "mediaType",
        "application/json");
  }

  private byte[] originalBytes(Map<String, Object> data) throws Exception {
    byte[] bytes = ipfs.cat((String) data.get("original_cid"));
    check(
        Arrays.equals(
            bytes, ((String) data.get("original_utf8")).getBytes(StandardCharsets.UTF_8)));
    check(Json.sha(bytes).equals(data.get("original_sha256")));
    return bytes;
  }

  public byte[] verify(Map<String, Object> row) throws Exception {
    var data = local(row);
    byte[] original = originalBytes(data);
    byte[] manifestBytes = ipfs.cat((String) row.get("manifest_cid"));
    check(Json.sha(manifestBytes).equals(row.get("manifest_sha256")));
    String expected =
        Json.write(
            Map.of(
                "schemaVersion",
                2,
                "event",
                Json.map((String) row.get("canonical_json")),
                "eventSha256",
                row.get("event_sha256"),
                "evidence",
                List.of(),
                "originalRequest",
                originalReference(data)));
    check(Arrays.equals(manifestBytes, expected.getBytes(StandardCharsets.UTF_8)));
    check(expected.equals(row.get("manifest_json")));
    return original;
  }
}
