package com.mt.wms.auth.sso;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * IAM 主体 → WMS 本地受限主体的显式映射。
 *
 * <p>契约 V1.1：无映射即拒绝，任何情况下都不得回退到 admin 或任何管理员账号。
 *
 * <p>表结构由 {@code db/sso/001-wms-sso-subject-map.sql} 创建；本地主体必须是
 * {@code auth_user} 中真实存在且启用的用户。
 */
@Repository
public class WmsSsoSubjectRepository {

  private final JdbcClient jdbc;

  public WmsSsoSubjectRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 映射结果：本地主体 + 租户范围。 */
  public record Binding(
      long localUserId,
      String localUsername,
      String companyCode,
      String zoneCode,
      boolean passwordChangeRequired) {}

  /**
   * 按 IAM subject 查映射，并校验本地账号仍然可用。
   *
   * <p>返回空表示：无映射 / 映射停用 / 本地账号不存在或已停用。调用方一律拒绝。
   */
  public Optional<Binding> findActive(String iamSubject) {
    if (iamSubject == null || iamSubject.isBlank()) return Optional.empty();
    return jdbc.sql(
            """
            SELECT u.id AS user_id, u.username, c.code AS company_code,
                   m.zone_code, u.password_change_required
              FROM wms_sso_subject_map m
              JOIN auth_user u ON u.id = m.local_user_id
              JOIN auth_company c ON c.id = u.company_id
             WHERE m.iam_subject = :subject
               AND m.audience = :audience
               AND m.enabled = 1
               AND u.status = 'ENABLED'
               AND u.login_enabled = TRUE
               AND u.deleted_at IS NULL
               AND c.status = 'ENABLED'
               AND m.company_code = c.code
               AND m.company_code IS NOT NULL
               AND length(trim(m.company_code)) > 0
             LIMIT 1
            """)
        .param("subject", iamSubject)
        .param("audience", "wms")
        .query(
            (rs, n) ->
                new Binding(
                    rs.getLong("user_id"),
                    rs.getString("username"),
                    rs.getString("company_code"),
                    rs.getString("zone_code"),
                    rs.getBoolean("password_change_required")))
        .optional();
  }

  /** 审计用：记录一次 SSO 兑换（不含票据原文与任何凭据）。 */
  public void recordExchange(String iamSubject, long localUserId, String sessionId, String result) {
    try {
      jdbc.sql(
              """
              INSERT INTO wms_sso_exchange_audit
                  (iam_subject, local_user_id, iam_session_id, result, created_at)
              VALUES (:subject, :localUser, :sessionId, :result, CURRENT_TIMESTAMP)
              """)
          .param("subject", iamSubject)
          .param("localUser", localUserId)
          .param("sessionId", sessionId == null ? "" : sessionId)
          .param("result", result)
          .update();
    } catch (Exception ignored) {
      // 审计失败不得影响主流程（但映射/兑换失败必须拒绝，见 service）
    }
  }
}
