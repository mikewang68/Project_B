package com.bdemo.iam.sso.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 统一注销通知的持久化（{@code iam.sso_logout_notify}）。
 *
 * <p>为什么落库而不是放内存：注销通知必须能跨进程重启存活，否则
 * 一次重启就会丢掉"欠业务系统一次撤销"的事实。表里带
 * {@code attempt_count} / {@code last_attempt_at} / {@code last_error}，
 * 便于运维查看哪些受众一直投递失败。
 */
@Mapper
public interface SsoLogoutMapper {

  /** 待投递记录。 */
  record Pending(
      String id,
      String sessionId,
      String subject,
      String audience,
      int attemptCount,
      Instant createdAt) {}

  /**
   * 幂等登记。已存在且尚未投递成功时不重复插入（openGauss 无 ON CONFLICT，
   * 用 WHERE NOT EXISTS 实现）。
   */
  @Update(
      "INSERT INTO iam.sso_logout_notify(id,jti,subject,audience,session_id,reason)"
          + " SELECT #{id},#{id},#{subject},#{audience},#{sid},#{reason}"
          + " WHERE NOT EXISTS (SELECT 1 FROM iam.sso_logout_notify WHERE id=#{id})")
  void insertPending(
      @Param("id") String id,
      @Param("sid") String sid,
      @Param("subject") String subject,
      @Param("audience") String audience,
      @Param("reason") String reason);

  /** 取未投递且未超过重试上限的记录。 */
  @Select(
      "SELECT id,session_id,subject,audience,attempt_count,created_at"
          + " FROM iam.sso_logout_notify"
          + " WHERE delivered_at IS NULL AND attempt_count < 10"
          + " ORDER BY created_at"
          + " LIMIT #{limit}")
  List<Pending> findPending(@Param("limit") int limit);

  /** 记录一次尝试（在真正发起请求前调用，避免异常路径漏记）。 */
  @Update(
      "UPDATE iam.sso_logout_notify"
          + " SET attempt_count = attempt_count + 1, last_attempt_at = CURRENT_TIMESTAMP"
          + " WHERE id = #{id}")
  void markAttempt(@Param("id") String id);

  /** 投递成功。 */
  @Update(
      "UPDATE iam.sso_logout_notify SET delivered_at = CURRENT_TIMESTAMP, last_error = ''"
          + " WHERE id = #{id}")
  void markDelivered(@Param("id") String id);

  /** 投递失败：记录原因，保留可重试状态。 */
  @Update(
      "UPDATE iam.sso_logout_notify SET last_error = #{error}"
          + " WHERE id = #{id}")
  void markFailed(@Param("id") String id, @Param("error") String error);

  /** 统计（诊断用）：待投递数量。 */
  @Select("SELECT COUNT(*) FROM iam.sso_logout_notify WHERE delivered_at IS NULL")
  int countPending();

  /** 统计（诊断用）：投递失败数量。 */
  @Select(
      "SELECT COUNT(*) FROM iam.sso_logout_notify"
          + " WHERE delivered_at IS NULL AND last_error <> ''")
  int countFailing();
}
