package com.bproject.trust.ingress;
import com.bproject.trust.shared.json.Json;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
/** Pure production and local simulation construction and transition rules. */
public final class GenericEventRules {
  private GenericEventRules() {}
  public static String canonical(EvidenceIngressSettings.Source source, GenericEventInput input, String id, String root, int version) {
    byte[] original = input.original().getBytes(StandardCharsets.UTF_8);
    String originalSha = Json.sha(original);
    var canonical = new TreeMap<String, Object>();
    canonical.put("schemaVersion", 2);
    canonical.put("recordKind", "GENERIC_V1");
    canonical.put("id", id);
    canonical.put("orgId", source.orgId());
    canonical.put("submittedBy", source.actor());
    canonical.put("rootId", root);
    canonical.put("version", version);
    canonical.put("supersedesId", input.supersedesId());
    canonical.put("event", input.event(source.sourceSystem()));
    canonical.put("evidence", List.of());
    canonical.put(
        "originalRequest",
        Map.of(
            "sha256",
            originalSha,
            "sizeBytes",
            original.length,
            "mediaType",
            "application/json"));
    String json = Json.write(canonical);
    if (json.getBytes(StandardCharsets.UTF_8).length > GenericEventInput.LIMIT)
      throw new IngressError(413, "REQUEST_TOO_LARGE", "事件内容过大");
    return json;
  }
  public static Map<String, Object> receipt(Map<String, Object> e) {
    boolean uncertain = Boolean.TRUE.equals(e.get("uncertain"));
    var out = new LinkedHashMap<String, Object>();
    for (var entry :
        Map.of(
                "id",
                "id",
                "sourceSystem",
                "source_system",
                "sourceEventId",
                "source_event_id",
                "rootId",
                "root_id",
                "version",
                "version",
                "supersedesId",
                "supersedes_id",
                "eventSha256",
                "event_sha256",
                "manifestCid",
                "manifest_cid",
                "manifestSha256",
                "manifest_sha256",
                "txId",
                "tx_id")
            .entrySet()) out.put(entry.getKey(), e.get(entry.getValue()));
    out.put("blockNumber", e.get("block_number"));
    out.put("originalSha256", e.get("original_sha256"));
    out.put("originalCid", e.get("original_cid"));
    out.put("fileState", e.get("file_state"));
    out.put("chainState", e.get("chain_state"));
    out.put("taskState", e.get("task_state"));
    out.put("outcomeUnknown", uncertain);
    out.put("errorCode", e.get("last_error_code"));
    out.put(
        "status",
        status(
            (String) e.get("task_state"),
            (String) e.get("chain_state"),
            ((Number) e.get("attempts")).intValue(),
            uncertain));
    out.put(
        "nextRetryAt",
        "READY".equals(e.get("task_state")) && ((Number) e.get("attempts")).intValue() > 0
            ? ((Timestamp) e.get("next_at")).toInstant().toString()
            : null);
    return out;
  }

  public static String status(String task, String chain, int attempts, boolean uncertain) {
    if ("DONE".equals(task) && "COMMITTED".equals(chain)) return "COMMITTED";
    if ("FAILED".equals(task)) return "ACTION_REQUIRED";
    if (uncertain || "CONFIRMING".equals(chain)) return "CONFIRMING";
    if ("RUNNING".equals(task)) return "PROCESSING";
    return attempts > 0 ? "RETRY_WAIT" : "ACCEPTED";
  }
  public static boolean retryQueued(String state, String chain) {
    if ("RUNNING".equals(state)) throw new IngressError(409, "TASK_RUNNING", "任务正在处理");
    if ("DONE".equals(state) && !"COMMITTED".equals(chain))
      throw new IngressError(409, "INTEGRITY_MISMATCH", "任务确认状态不一致");
    boolean queued = "FAILED".equals(state);
    if (!Set.of("DONE", "READY", "FAILED").contains(state))
      throw new IngressError(409, "INTEGRITY_MISMATCH", "任务状态不一致");
    return queued;
  }
}
