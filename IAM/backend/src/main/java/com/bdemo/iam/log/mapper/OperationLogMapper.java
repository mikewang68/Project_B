package com.bdemo.iam.log.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** IAM 操作日志写入统一日志表 iam.iam_audit（仅 IAM 所有，运行账号仅允许追加）。 */
@Mapper
public interface OperationLogMapper {

  @Insert(
      """
      INSERT INTO iam.iam_audit
        (id, kind, username, user_id, module, action, target, detail,
         request_method, request_uri, ip, result, duration_ms, created_at)
      VALUES
        (#{id}, #{kind}, #{username}, #{userId}, #{module}, #{action}, #{target}, #{detail},
         #{requestMethod}, #{requestUri}, #{ip}, #{result}, #{durationMs}, #{now})
      """)
  int insert(
      @Param("id") String id,
      @Param("kind") String kind,
      @Param("username") String username,
      @Param("userId") String userId,
      @Param("module") String module,
      @Param("action") String action,
      @Param("target") String target,
      @Param("detail") String detail,
      @Param("requestMethod") String requestMethod,
      @Param("requestUri") String requestUri,
      @Param("ip") String ip,
      @Param("result") String result,
      @Param("durationMs") Integer durationMs,
      @Param("now") LocalDateTime now);
}
