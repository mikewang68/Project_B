package com.bproject.trust.archiving;

import static org.junit.jupiter.api.Assertions.*;

import com.bproject.trust.shared.web.ApiError;
import java.util.*;
import org.junit.jupiter.api.Test;

class AttestationRecordTest {
  @Test
  void manifestCannotBeChangedWithoutDetection() {
    var a = new HashMap<String, Object>();
    for (String k :
        List.of(
            "id",
            "orgId",
            "rootId",
            "version",
            "supersedesId",
            "eventSha256",
            "manifestCid",
            "manifestSha256",
            "submittedBy")) a.put(k, "same");
    var b = new HashMap<>(a);
    b.put("manifestCid", "other");
    assertThrows(ApiError.class, () -> AttestationRecord.assertMatches(a, b));
  }
}
