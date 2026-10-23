package com.mt.wms.auth.sso;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * WMS 本地会话 ↔ IAM 会话（sid）的绑定表与撤销表。
 *
 * <p>用途：Portal/IAM 登出后撤销 sid，WMS 必须在**每个受保护请求**上校验该会话仍然有效，
 * 一旦失效立即销毁本地 HttpSession。删除 Cookie 不能替代这一步。
 *
 * <p><b>G5 修复的缺陷</b>：原实现在 {@code revoke} 里先 {@code markRevoked(sid)}、
 * 紧接着 {@code unbindByIamSid(sid)} 把 BINDINGS 条目删掉。而 Guard 过滤器的逻辑是
 * "binding == null 视为非 SSO 会话，直接放行"。于是撤销动作删除了检查依据本身，
 * 撤销标记永远查不到，旧 JSESSIONID 继续返回 200。
 *
 * <p>修复方式：
 *
 * <ol>
 *   <li>撤销时**保留**绑定，并在绑定上打 {@code revoked} 标志；
 *   <li>另设 {@code REVOKED_WMS_SESSIONS}（按本地 session id 索引）作为独立判据，
 *       即使绑定被清理或并发移除，仍能识别已撤销会话；
 *   <li>同一 IAM sid 允许多个本地会话（多标签页/多设备），撤销时全部失效。
 * </ol>
 *
 * <p>本表是进程内的，重启后清空。生产多实例部署应替换为共享存储，并在文档标注该限制。
 */
@Component
public class WmsSsoLogoutRegistry {

  /** wmsSessionId -> binding（撤销后保留，靠 revoked 标志识别） */
  private static final Map<String, Binding> BINDINGS = new ConcurrentHashMap<>();

  /** iamSessionId -> wmsSessionIds（一个 IAM 会话可对应多个本地会话） */
  private static final Map<String, Set<String>> BY_IAM_SID = new ConcurrentHashMap<>();

  /** 已撤销的 IAM sid。绑定丢失时仍能识别撤销语义。 */
  private static final Set<String> REVOKED_SIDS = ConcurrentHashMap.newKeySet();

  /** 已撤销的本地 session id。撤销的主判据，不依赖绑定是否还在。 */
  private static final Set<String> REVOKED_WMS_SESSIONS = ConcurrentHashMap.newKeySet();

  public static final class Binding {
    private final String iamSessionId;
    private final String iamSubject;
    private final long boundAt;
    private volatile boolean revoked;

    Binding(String iamSessionId, String iamSubject, long boundAt) {
      this.iamSessionId = iamSessionId;
      this.iamSubject = iamSubject;
      this.boundAt = boundAt;
    }

    public String iamSessionId() {
      return iamSessionId;
    }

    public String iamSubject() {
      return iamSubject;
    }

    public long boundAt() {
      return boundAt;
    }

    public boolean revoked() {
      return revoked;
    }

    void markRevoked() {
      this.revoked = true;
    }
  }

  public static void bind(String wmsSessionId, String iamSessionId, String iamSubject) {
    if (wmsSessionId == null || wmsSessionId.isBlank()) {
      return;
    }
    // A new exchange can rebind a rotated local session to another IAM sid.
    // Remove the previous reverse index before installing the new binding.
    unbind(wmsSessionId);
    String sid = iamSessionId == null ? "" : iamSessionId;
    BINDINGS.put(wmsSessionId, new Binding(sid, iamSubject, System.currentTimeMillis()));
    if (!sid.isBlank()) {
      BY_IAM_SID.computeIfAbsent(sid, key -> ConcurrentHashMap.newKeySet()).add(wmsSessionId);
    }
  }

  /**
   * 会话真正销毁后清理（仅由 Guard 在 invalidate 之后调用）。
   * 注意：这不是撤销入口——撤销请用 {@link #revokeByIamSid(String)}。
   */
  public static void unbind(String wmsSessionId) {
    if (wmsSessionId == null) {
      return;
    }
    Binding b = BINDINGS.remove(wmsSessionId);
    if (b != null && !b.iamSessionId().isBlank()) {
      BY_IAM_SID.computeIfPresent(
          b.iamSessionId(),
          (key, set) -> {
            set.remove(wmsSessionId);
            return set.isEmpty() ? null : set;
          });
    }
  }

  public static Binding get(String wmsSessionId) {
    return wmsSessionId == null ? null : BINDINGS.get(wmsSessionId);
  }

  /**
   * 撤销某 IAM sid 对应的**全部**本地会话。
   *
   * <p>幂等：重复调用返回相同的已撤销集合。
   * 不删除绑定，只打标志——否则 Guard 会把已撤销会话误判为原生会话而放行。
   *
   * @return 本次受影响的本地 session id 集合（可能为空，但撤销语义仍成立）
   */
  public static Set<String> revokeByIamSid(String iamSessionId) {
    if (iamSessionId == null || iamSessionId.isBlank()) {
      return Set.of();
    }
    REVOKED_SIDS.add(iamSessionId);

    Set<String> sessions = BY_IAM_SID.get(iamSessionId);
    if (sessions == null || sessions.isEmpty()) {
      return Set.of();
    }
    Set<String> affected = Set.copyOf(sessions);
    for (String wms : affected) {
      REVOKED_WMS_SESSIONS.add(wms);
      Binding b = BINDINGS.get(wms);
      if (b != null) {
        b.markRevoked();
      }
    }
    return affected;
  }

  /** 兼容旧调用名：按 IAM sid 撤销并返回首个受影响的本地会话。 */
  public static String unbindByIamSid(String iamSessionId) {
    Set<String> affected = revokeByIamSid(iamSessionId);
    return affected.isEmpty() ? null : affected.iterator().next();
  }

  /** 标记某 IAM sid 已撤销。幂等。 */
  public static void markRevoked(String iamSessionId) {
    if (iamSessionId != null && !iamSessionId.isBlank()) {
      REVOKED_SIDS.add(iamSessionId);
    }
  }

  /** 该 IAM sid 是否已被撤销。 */
  public static boolean isRevoked(String iamSessionId) {
    return iamSessionId != null && !iamSessionId.isBlank() && REVOKED_SIDS.contains(iamSessionId);
  }

  /** 该本地会话是否已被撤销（不依赖绑定是否存在）。 */
  public static boolean isSessionRevoked(String wmsSessionId) {
    return wmsSessionId != null
        && !wmsSessionId.isBlank()
        && REVOKED_WMS_SESSIONS.contains(wmsSessionId);
  }

  /** 供诊断：已撤销的 IAM sid 数。 */
  public static int revokedCount() {
    return REVOKED_SIDS.size();
  }

  /** 供诊断：已撤销的本地会话数。 */
  public static int revokedSessionCount() {
    return REVOKED_WMS_SESSIONS.size();
  }

  public static int size() {
    return BINDINGS.size();
  }

  /**
   * 仅供测试：清空全部进程内状态。
   *
   * <p>生产代码不得调用——撤销语义依赖这些集合持续存在。
   * 之所以需要它，是因为注册表是静态的，单元测试之间会互相污染。
   */
  static void resetForTests() {
    BINDINGS.clear();
    BY_IAM_SID.clear();
    REVOKED_SIDS.clear();
    REVOKED_WMS_SESSIONS.clear();
  }
}
