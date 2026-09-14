package com.bproject.trust.archiving;

import com.bproject.trust.shared.web.ApiError;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AttestationRecord {
  private AttestationRecord() {}

  public static Map<String, Object> record(Map<String, Object> e) {
    return Map.of(
        "id",
        e.get("id"),
        "orgId",
        e.get("org_id"),
        "rootId",
        e.get("root_id"),
        "version",
        e.get("version"),
        "supersedesId",
        Objects.toString(e.get("supersedes_id"), ""),
        "eventSha256",
        e.get("event_sha256"),
        "manifestCid",
        e.get("manifest_cid"),
        "manifestSha256",
        e.get("manifest_sha256"),
        "submittedBy",
        e.get("submitted_by"));
  }

  public static void assertMatches(Map<String, Object> expected, Map<String, Object> actual) {
    for (var k :
        List.of(
            "id",
            "orgId",
            "rootId",
            "version",
            "supersedesId",
            "eventSha256",
            "manifestCid",
            "manifestSha256",
            "submittedBy"))
      if (!Objects.toString(expected.get(k), "").equals(Objects.toString(actual.get(k), "")))
        throw new ApiError(409, "链上记录与本地记录不一致：" + k);
  }
}
