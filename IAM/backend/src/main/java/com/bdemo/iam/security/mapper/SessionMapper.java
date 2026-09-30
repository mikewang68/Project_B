package com.bdemo.iam.security.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SessionMapper {
  @Insert("INSERT INTO iam.iam_session(id,user_id,expires_at) VALUES(#{id},#{user},#{expires})")
  void insert(
      @Param("id") String id, @Param("user") String user, @Param("expires") Instant expires);

  @Select(
      "SELECT COUNT(*) FROM iam.iam_session WHERE id=#{id} AND user_id=#{user} AND revoked_at IS"
          + " NULL AND expires_at>CURRENT_TIMESTAMP")
  int active(@Param("id") String id, @Param("user") String user);

  @Update(
      "UPDATE iam.iam_session SET revoked_at=CURRENT_TIMESTAMP WHERE user_id=#{user} AND revoked_at"
          + " IS NULL")
  void revokeUser(String user);
}
