package com.bproject.trust.ports;

import java.util.Map;
import java.util.function.Consumer;

public interface LedgerGateway {
  Map<String, Object> find(String org, String id) throws Exception;

  Map<String, Object> submit(Map<String, Object> record, Consumer<String> prepared)
      throws Exception;

  boolean healthy();
}
