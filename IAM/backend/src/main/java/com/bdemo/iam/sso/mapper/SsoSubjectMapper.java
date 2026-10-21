package com.bdemo.iam.sso.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.*;

/**
 * 主体映射表访问。
 *
 * <p>注意：openGauss 不支持 {@code ON CONFLICT}，因此 upsert 拆成
 * {@code UPDATE} + {@code INSERT ... WHERE NOT EXISTS} 两步。
 */
@Mapper
public interface SsoSubjectMapper {

  @Select(
      "SELECT subject, audience, local_subject, company_code, org_code, domain_code, zone_code, enabled"
          + " FROM iam.sso_subject_binding WHERE subject=#{subject} AND audience=#{audience}")
  Map<String, Object> find(@Param("subject") String subject, @Param("audience") String audience);

  @Update(
      "UPDATE iam.sso_subject_binding SET local_subject=#{local}, company_code=#{company},"
          + " org_code=#{org}, domain_code=#{domain}, zone_code=#{zone}, enabled=#{enabled},"
          + " updated_at=CURRENT_TIMESTAMP"
          + " WHERE subject=#{subject} AND audience=#{audience}")
  int update(
      @Param("subject") String subject,
      @Param("audience") String audience,
      @Param("local") String local,
      @Param("company") String company,
      @Param("org") String org,
      @Param("domain") String domain,
      @Param("zone") String zone,
      @Param("enabled") int enabled);

  @Insert(
      "INSERT INTO iam.sso_subject_binding"
          + "(subject,audience,local_subject,company_code,org_code,domain_code,zone_code,enabled)"
          + " SELECT #{subject},#{audience},#{local},#{company},#{org},#{domain},#{zone},#{enabled}"
          + " WHERE NOT EXISTS (SELECT 1 FROM iam.sso_subject_binding"
          + "                    WHERE subject=#{subject} AND audience=#{audience})")
  int insertIfAbsent(
      @Param("subject") String subject,
      @Param("audience") String audience,
      @Param("local") String local,
      @Param("company") String company,
      @Param("org") String org,
      @Param("domain") String domain,
      @Param("zone") String zone,
      @Param("enabled") int enabled);

  /** upsert：先更新，未命中再插入。调用方需在事务内。 */
  default void upsert(
      String subject,
      String audience,
      String local,
      String company,
      String org,
      String domain,
      String zone,
      int enabled) {
    if (update(subject, audience, local, company, org, domain, zone, enabled) == 0) {
      insertIfAbsent(subject, audience, local, company, org, domain, zone, enabled);
    }
  }
}
