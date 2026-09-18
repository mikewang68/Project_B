package com.bdemo.iam.log.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * IAM 操作日志写入统一日志表 sys.sys_operation_log（表归 SYS 所有，iam_app 被授予 SELECT/INSERT）。
 */
@Mapper
public interface OperationLogMapper {

    @Insert("""
            INSERT INTO sys.sys_operation_log
              (id, kind, username, user_id, module, action, target, detail,
               request_method, request_uri, ip, result, duration_ms, created_at)
            VALUES
              (#{id}, #{kind}, #{username}, #{userId}, #{module}, #{action}, #{target}, #{detail},
               #{requestMethod}, #{requestUri}, #{ip}, #{result}, #{durationMs}, #{now})
            """)
    int insert(@Param("id") String id,
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
