package com.bdemo.iam.sso;

import com.bdemo.iam.security.JwtAuthFilter;
import com.bdemo.iam.security.LoginUser;
import com.bdemo.iam.sso.mapper.SsoLogoutMapper;
import com.bdemo.iam.sso.mapper.SsoTicketMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 平台侧统一注销（SLO）编排。
 *
 * <p>一次用户登出需要做三件事，缺一不可：
 *
 * <ol>
 *   <li><b>撤销未消费票据</b>：否则刚签发、还没换走的票在登出后仍可兑换；
 *   <li><b>通知已建立业务会话的系统</b>：业务系统的 {@code HttpSession} 不会因为
 *       IAM 登出而自动消失，必须服务端撤销；
 *   <li><b>记录结果</b>：投递失败要留痕并可重试，不能静默当作成功。
 * </ol>
 *
 * <p><b>与 {@code SsoTicketController#revoke} 的区别</b>：那个端点要求 IAM 登录态、
 * 以用户身份调用、只撤自己的未消费票据，<b>不是</b>服务间通道。
 * 真正的下游撤销由本类的 {@link SsoLogoutNotifier} 以服务身份发起。
 */
@Service
public class SsoLogoutCoordinator {

  private static final Logger log = LoggerFactory.getLogger(SsoLogoutCoordinator.class);

  private final SsoTicketMapper tickets;
  private final SsoLogoutMapper logoutRecords;
  private final SsoLogoutNotifier notifier;

  public SsoLogoutCoordinator(
      SsoTicketMapper tickets, SsoLogoutMapper logoutRecords, SsoLogoutNotifier notifier) {
    this.tickets = tickets;
    this.logoutRecords = logoutRecords;
    this.notifier = notifier;
  }

  /** 从请求取 IAM 会话 id（登录 JWT 的 jti，V1.1 起由 JwtAuthFilter 写入）。 */
  public static String sessionIdOf(HttpServletRequest request) {
    Object v = request.getAttribute("iam.sessionId");
    return v == null ? "" : String.valueOf(v);
  }

  /**
   * 执行平台侧 SLO。
   *
   * @param user    当前登录用户（可为 null，例如会话已失效时）
   * @param sid     IAM 会话 id
   * @param request 用于取审计信息
   */
  public Result logout(LoginUser user, String sid, HttpServletRequest request) {
    String subject = user == null ? "" : user.getUserId();

    // 1) 撤销该会话下所有未被消费的票据
    int revokedTickets = 0;
    if (sid != null && !sid.isBlank()) {
      try {
        revokedTickets = tickets.revokeBySession(sid);
      } catch (RuntimeException e) {
        log.warn("撤销未消费票据失败: {}", e.getClass().getSimpleName());
      }
    }

    // 2) 找出该会话签发过票的受众 —— 这些系统的本地会话需要被撤销
    List<String> audiences = List.of();
    if (sid != null && !sid.isBlank()) {
      try {
        audiences = tickets.findAudiencesBySession(sid);
      } catch (RuntimeException e) {
        log.warn("查询会话受众失败: {}", e.getClass().getSimpleName());
      }
    }

    // 3) 以服务身份通知各系统（失败会落库重试，不影响本地登出成功）
    List<String> delivered = List.of();
    if (!audiences.isEmpty()) {
      try {
        delivered = notifier.notifyLogout(sid, subject, audiences, "PORTAL_LOGOUT");
      } catch (RuntimeException e) {
        log.warn("注销通知派发异常: {}", e.getClass().getSimpleName());
      }
    }

    Result result = new Result(sid, revokedTickets, audiences, delivered,
        safeCountPending(), safeCountFailing());
    log.info("SLO 完成: audiences={}, delivered={}, ticketsRevoked={}, pending={}, failing={}",
        audiences.size(), delivered.size(), revokedTickets,
        result.pendingNotifies(), result.failingNotifies());
    return result;
  }

  private int safeCountPending() {
    try {
      return logoutRecords.countPending();
    } catch (RuntimeException e) {
      return -1;
    }
  }

  private int safeCountFailing() {
    try {
      return logoutRecords.countFailing();
    } catch (RuntimeException e) {
      return -1;
    }
  }

  /**
   * 注销结果。
   *
   * @param revokedTickets      撤销的未消费票据数
   * @param audiences           需要通知的受众（该会话下已消费票据的受众）
   * @param deliveredAudiences  本次投递成功的受众
   * @param pendingNotifies     全表待投递数（含历史，仅作运维参考）
   * @param failingNotifies     全表投递失败数（含历史，仅作运维参考）
   */
  public record Result(
      String sessionId,
      int revokedTickets,
      List<String> audiences,
      List<String> deliveredAudiences,
      int pendingNotifies,
      int failingNotifies) {

    public Map<String, Object> toBody() {
      Map<String, Object> out = new HashMap<>();
      out.put("sessionId", sessionId);
      out.put("revokedTickets", revokedTickets);
      out.put("notifiedAudiences", audiences);
      out.put("deliveredAudiences", deliveredAudiences);
      out.put("pendingNotifies", pendingNotifies);
      out.put("failingNotifies", failingNotifies);

      // 语义："下游是否已全部撤销完成"。
      //
      // 注意 two 种情况：
      //   - 本会话没有任何已消费票据 → 下游从未建立会话，无需撤销，视为 complete；
      //   - 有已消费票据 → 必须全部投递成功才算 complete。
      //
      // 不能用全表 pending/failing 判断：那是历史累计值，会让本次结果失真。
      boolean complete = deliveredAudiences.size() == audiences.size();
      out.put("downstreamComplete", complete);
      if (!complete) {
        out.put("downstreamPending", audiences.stream()
            .filter(a -> !deliveredAudiences.contains(a))
            .toList());
      }
      return out;
    }
  }

  /** 供 AuthController 复用：取当前 LoginUser。 */
  static LoginUser currentUser() {
    return JwtAuthFilter.currentUser();
  }
}
