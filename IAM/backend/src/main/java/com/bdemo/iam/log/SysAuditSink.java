package com.bdemo.iam.log;

import java.util.Map;

/**
 * Optional external adapter contract. SYS maintainers must agree authentication and idempotent
 * receipts.
 */
public interface SysAuditSink {
  /**
   * Return the same immutable IAM audit id only after the external system has durably accepted it.
   */
  String append(String auditId, Map<String, Object> event) throws Exception;
}
