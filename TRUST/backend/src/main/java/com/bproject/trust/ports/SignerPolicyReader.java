package com.bproject.trust.ports;

import java.util.List;
import java.util.Map;

/** Read-only policy observations, one result from each configured organization. */
public interface SignerPolicyReader {
  record Observation(String organization, boolean available, Map<String, Object> policy) {}
  List<Observation> read(String org, String source, String fingerprint);
}
