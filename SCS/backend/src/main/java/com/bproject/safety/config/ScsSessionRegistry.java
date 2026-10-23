package com.bproject.safety.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SCS 的本地会话表（G6）。
 *
 * <p>契约要求：IAM 短票是一次性凭据，<b>不能</b>被前端反复当作业务请求凭据。
 * 正确形态是「票据 → 一次性交换 → 本地会话」，后续请求用本地会话。
 *
 * <p>本类只负责会话登记的存取与撤销标记，不做验签（验签在
 * {@link ScsIamTicketValidator} / {@link JwtVerifier}）。
 *
 * <p><b>已知限制</b>：进程内 {@link ConcurrentHashMap}，重启即清空，多实例不共享。
 * 生产多实例部署时必须替换为共享存储（Redis/DB），否则撤销不跨节点。
 */
public final class ScsSessionRegistry {

  private static final Logger log = LoggerFactory.getLogger(ScsSessionRegistry.class);

  /** 本地 session id → 会话记录 */
  private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

  /** 已撤销的 IAM sid */
  private static final Set<String> REVOKED_IAM_SIDS = ConcurrentHashMap.newKeySet();

  /** 已撤销的本地 session id（不依赖记录是否还在） */
  private static final Set<String> REVOKED_SESSIONS = ConcurrentHashMap.newKeySet();

  private ScsSessionRegistry() {}

  public record Session(
      String localSessionId,
      String iamSubject,
      String iamSid,
      Set<String> roles,
      Set<String> permissions,
      String scope,
      Instant boundAt) {}

  public static void bind(
      String localSessionId, String iamSubject, String iamSid,
      Set<String> roles, Set<String> permissions, String scope) {
    if (localSessionId == null || localSessionId.isBlank()) {
      return;
    }
    SESSIONS.put(localSessionId, new Session(
        localSessionId, iamSubject, nullToEmpty(iamSid),
        roles == null ? Set.of() : Set.copyOf(roles),
        permissions == null ? Set.of() : Set.copyOf(permissions),
        nullToEmpty(scope), Instant.now()));
  }

  public static Session get(String localSessionId) {
    return localSessionId == null ? null : SESSIONS.get(localSessionId);
  }

  public static void unbind(String localSessionId) {
    if (localSessionId != null) {
      SESSIONS.remove(localSessionId);
    }
  }

  /** 撤销某 IAM 会话对应的全部本地会话；幂等。 */
  public static int revokeByIamSid(String iamSid) {
    if (iamSid == null || iamSid.isBlank()) {
      return 0;
    }
    REVOKED_IAM_SIDS.add(iamSid);
    int count = 0;
    for (Map.Entry<String, Session> e : SESSIONS.entrySet()) {
      if (iamSid.equals(e.getValue().iamSid())) {
        REVOKED_SESSIONS.add(e.getKey());
        count++;
      }
    }
    log.info("已撤销 IAM 会话对应的 SCS 会话数={}", count);
    return count;
  }

  /** 该本地会话是否已撤销（不依赖记录是否还存在）。 */
  public static boolean isSessionRevoked(String localSessionId) {
    if (localSessionId == null || localSessionId.isBlank()) {
      return false;
    }
    if (REVOKED_SESSIONS.contains(localSessionId)) {
      return true;
    }
    Session s = SESSIONS.get(localSessionId);
    return s != null && REVOKED_IAM_SIDS.contains(s.iamSid());
  }

  /** 从 servlet 会话读取本地 session id；不存在返回 null。 */
  public static String sessionIdOf(HttpSession session) {
    return session == null ? null : session.getId();
  }

  /** 解析 IAM 断言中的 sid（票据里的 sessionId）。 */
  public static String sidOf(JsonNode claims) {
    if (claims == null) {
      return "";
    }
    return claims.path("sessionId").asText("");
  }

  private static String nullToEmpty(String v) {
    return v == null ? "" : v;
  }

  /** 仅供测试：清空进程内状态。 */
  static void resetForTests() {
    SESSIONS.clear();
    REVOKED_IAM_SIDS.clear();
    REVOKED_SESSIONS.clear();
  }

  public static int size() {
    return SESSIONS.size();
  }

  public static int revokedCount() {
    return REVOKED_IAM_SIDS.size();
  }
}
