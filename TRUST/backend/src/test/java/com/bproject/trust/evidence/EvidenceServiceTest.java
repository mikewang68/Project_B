package com.bproject.trust.evidence;

import static org.junit.jupiter.api.Assertions.*;

import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import java.util.*;
import org.junit.jupiter.api.Test;

class EvidenceServiceTest {
  @Test
  void changedEvidenceIndexCannotPassByRehashingReplacement() {
    var original =
        Map.<String, Object>of(
            "id",
            "one",
            "filename",
            "proof.pdf",
            "mime",
            "application/pdf",
            "size_bytes",
            20,
            "sha256",
            "original");
    String canonical = Json.write(Map.of("evidence", List.of(original)));
    var replacement = new HashMap<>(original);
    replacement.put("sha256", "replacement");
    assertThrows(
        ApiError.class, () -> EvidenceService.assertBindings(canonical, List.of(replacement)));
    assertThrows(ApiError.class, () -> EvidenceService.assertBindings(canonical, List.of()));
    assertDoesNotThrow(() -> EvidenceService.assertBindings(canonical, List.of(original)));
  }

  @Test
  void filesAreTypedByTheirContent() {
    assertThrows(
        ApiError.class, () -> EvidenceService.detect("<script>alert(1)</script>".getBytes()));
    assertEquals("application/pdf", EvidenceService.detect("%PDF-1.4".getBytes()));
  }
}
