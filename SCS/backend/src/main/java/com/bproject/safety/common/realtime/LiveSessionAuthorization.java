package com.bproject.safety.common.realtime;

import com.bproject.safety.config.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

/**
 * Re-check central authorization before delivery; no permissions are trusted from the socket client.
 *
 * <p><b>IAM 抖动 ≠ 会话撤销</b>：{@link ScsIamTicketValidator#current} 在 IAM 不可达/超时/5xx 时抛
 * {@link ScsIamTicketValidator.IamUnavailableException}，本类据此<b>不注销会话</b>，而是沿用最近一次
 * 成功解析的授权继续投递（宽限期内）。只有 IAM 明确判定"会话失效"、本地注册表已撤销/解绑、
 * 或绑定映射失效时才抛出真正需要断开连接的异常。
 *
 * <p>否则 IAM 一次抖动会让广播循环把全部在线连接逐个关掉，客户端按退避重连、每次握手又打一次 IAM，
 * 抖动被放大成全站不可用。
 */
@Service
public class LiveSessionAuthorization {
  private static final Logger log = LoggerFactory.getLogger(LiveSessionAuthorization.class);

  /** 本次事件对该连接暂停投递（IAM 不可用且无可用缓存授权），但不得关闭连接。 */
  public static class DeliverySuspendedException extends RuntimeException {
    public DeliverySuspendedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private record CachedActor(ScsLocalPolicy.Actor actor, long atMillis) {}

  private final ScsIamTicketValidator iam;
  private final ScsLocalPolicy policy;
  private final boolean required;
  private final long graceMillis;
  /** 最近一次成功解析的授权：IAM 短时不可用时按此继续投递。 */
  private final Map<String, CachedActor> lastKnown = new ConcurrentHashMap<>();

  @org.springframework.beans.factory.annotation.Autowired
  public LiveSessionAuthorization(
      ScsIamTicketValidator iam,
      ScsLocalPolicy policy,
      @Value("${app.security.browser-sso-only:${app.security.enabled:true}}") boolean required,
      @Value("${app.security.iam-outage-grace-seconds:600}") long graceSeconds) {
    this.iam = iam;
    this.policy = policy;
    this.required = required;
    this.graceMillis = Math.max(0, graceSeconds) * 1000L;
  }

  /** 兼容既有测试与装配（默认 10 分钟宽限）。 */
  public LiveSessionAuthorization(ScsIamTicketValidator iam, ScsLocalPolicy policy, boolean required) {
    this(iam, policy, required, 600);
  }

  public ScsLocalPolicy.Actor current(WebSocketSession session) {
    var value = session.getAttributes().get("SCS_WS_BINDING");
    if (!(value instanceof ScsSessionRegistry.Session binding)) {
      if (!required) return null;
      throw new IllegalStateException("Missing business session");
    }
    String localId = binding.localSessionId();
    if (ScsSessionRegistry.isSessionRevoked(localId) || ScsSessionRegistry.get(localId) == null) {
      forget(localId);
      throw new IllegalStateException("Revoked business session");
    }
    try {
      ScsLocalPolicy.Actor actor = policy.resolve(iam.current(binding.iamSubject(), binding.iamSid()));
      lastKnown.put(localId, new CachedActor(actor, System.currentTimeMillis()));
      return actor;
    } catch (ScsIamTicketValidator.IamUnavailableException unavailable) {
      CachedActor cached = lastKnown.get(localId);
      long now = System.currentTimeMillis();
      // graceMillis=0 表示不启用宽限：此时不得沿用任何缓存（<= 会让 0 宽限也命中）
      if (cached != null && graceMillis > 0 && now - cached.atMillis() < graceMillis) {
        log.debug("IAM 暂不可用，按最近授权继续投递 localSession={}", localId);
        return cached.actor();
      }
      // 超过宽限期仍无法确认：暂停投递，但保持连接（重连只会加重 IAM 压力）
      throw new DeliverySuspendedException("IAM 暂不可用且无可用缓存授权", unavailable);
    }
  }

  /** 连接关闭/被撤销时清理缓存，避免随会话数增长。 */
  public void forget(String localSessionId) {
    if (localSessionId != null) {
      lastKnown.remove(localSessionId);
    }
  }

  public boolean permits(ScsLocalPolicy.Actor actor, String eventType) {
    if (actor == null) return !required;
    if (actor.admin()) return true;
    String permission;
    if (eventType.startsWith("alert.")) permission = "safety:module:alert:view";
    else if (eventType.startsWith("ai.")) permission = "safety:module:ai:view";
    else if (eventType.startsWith("person.")) permission = "safety:person:map:view";
    else if (eventType.startsWith("fence.")) permission = "safety:fence:list:view";
    else if (eventType.startsWith("collision.")) permission = "safety:module:collision:view";
    else if (eventType.startsWith("rule.")) permission = "safety:module:rule:view";
    else if (eventType.startsWith("ops.")) permission = "safety:module:ops:view";
    else return false;
    return actor.permissions().contains(permission);
  }
}
