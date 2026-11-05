package com.bdemo.iam.log;

import com.bdemo.iam.log.mapper.OperationLogMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 显式操作日志记录（简单可靠，不引入 AOP 框架）。关键业务事务与审计共同提交；失败不得吞掉。 字段与 SYS 前端 SysLog
 * 对齐：kind(login/operation)、module(auth/user/role)、
 * action(login/logout/add/edit/delete/...)、target、detail、result(success/fail)。
 */
@Service
public class OperationLogger {

  private static final Logger log = LoggerFactory.getLogger(OperationLogger.class);

  private final OperationLogMapper mapper;

  public OperationLogger(OperationLogMapper mapper) {
    this.mapper = mapper;
  }

  /** Called after the failed creation transaction ends, so the warning survives its rollback. */
  @org.springframework.transaction.annotation.Transactional(
      propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public void usernameConflict(String requestedUsername, String reason) {
    String target = requestedUsername == null ? "" : requestedUsername.trim();
    if (!target.matches("[A-Za-z0-9_.-]{3,64}")) target = "INVALID_USERNAME";
    target = target.toLowerCase(java.util.Locale.ROOT);
    var actor = com.bdemo.iam.security.JwtAuthFilter.currentUser();
    String source = "UNIQUE_CONSTRAINT".equals(reason) ? "UNIQUE_CONSTRAINT" : "PRECHECK";
    log.warn("Username creation rejected: actor={}, target={}, source={}",
        actor == null ? "SYSTEM" : actor.getUserId(), target, source);
    record(actor == null ? "SYSTEM" : actor.getUsername(),
        actor == null ? null : actor.getUserId(), "operation", "user", "USERNAME_CONFLICT",
        target, "Username conflict rejected (" + source + ")", "POST", null, null, "fail");
  }

  public void record(
      String username,
      String userId,
      String kind,
      String module,
      String action,
      String target,
      String detail,
      String method,
      String uri,
      String ip,
      String result) {
    mapper.insert(
        "l" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
        kind,
        username,
        userId,
        module,
        action,
        target,
        detail,
        method,
        uri,
        ip,
        normalize(result),
        null,
        LocalDateTime.now());
  }

  private String normalize(String result) {
    return "fail".equalsIgnoreCase(result) || "failed".equalsIgnoreCase(result)
        ? "fail"
        : "success";
  }
}
