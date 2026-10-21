package com.bdemo.iam.sso.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SsoTicketMapper {

  @Insert(
      "INSERT INTO iam.sso_ticket"
          + "(jti,subject,username,audience,session_id,company_code,org_code,domain_code,zone_code,expires_at)"
          + " VALUES(#{jti},#{subject},#{username},#{audience},#{sessionId},#{company},#{org},#{domain},#{zone},#{expires})")
  void insert(
      @Param("jti") String jti,
      @Param("subject") String subject,
      @Param("username") String username,
      @Param("audience") String audience,
      @Param("sessionId") String sessionId,
      @Param("company") String company,
      @Param("org") String org,
      @Param("domain") String domain,
      @Param("zone") String zone,
      @Param("expires") Instant expires);

  @Select(
      "SELECT COUNT(*) FROM iam.sso_ticket"
          + " WHERE jti=#{jti} AND subject=#{subject} AND audience=#{audience}"
          + "   AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP")
  int existsActive(
      @Param("jti") String jti, @Param("subject") String subject, @Param("audience") String audience);

  /**
   * 原子消费：只有第一次能更新到 1 行；并发或重放返回 0。
   *
   * <p>条件里带 consumed_at IS NULL 保证 CAS 语义。
   */
  @Update(
      "UPDATE iam.sso_ticket SET consumed_at=CURRENT_TIMESTAMP, consumed_by=#{consumedBy}"
          + " WHERE jti=#{jti} AND consumed_at IS NULL AND revoked_at IS NULL"
          + "   AND expires_at > CURRENT_TIMESTAMP")
  int consume(@Param("jti") String jti, @Param("consumedBy") String consumedBy);

  @Update(
      "UPDATE iam.sso_ticket SET revoked_at=CURRENT_TIMESTAMP"
          + " WHERE session_id=#{sessionId} AND revoked_at IS NULL AND consumed_at IS NULL")
  void revokeBySession(@Param("sessionId") String sessionId);

  @Update(
      "UPDATE iam.sso_ticket SET revoked_at=CURRENT_TIMESTAMP"
          + " WHERE subject=#{subject} AND audience=#{audience}"
          + "   AND revoked_at IS NULL AND consumed_at IS NULL")
  void revokeBySubject(@Param("subject") String subject, @Param("audience") String audience);

  @Select(
      "SELECT COUNT(*) FROM iam.sso_ticket"
          + " WHERE audience=#{audience} AND consumed_at IS NOT NULL"
          + "   AND consumed_at > CURRENT_TIMESTAMP - INTERVAL '10 minutes'")
  int consumedRecently(@Param("audience") String audience);
}
