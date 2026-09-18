package com.bdemo.sys.log.mapper;

import com.bdemo.sys.log.domain.SysLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LogMapper {

    String FILTER = """
            <script>
            SELECT id, kind, username, module, action, target, detail, ip, result,
                   created_at AS createdAt
            FROM sys_operation_log
            <where>
              <if test="kind != null and kind != ''"> AND kind = #{kind} </if>
              <if test="module != null and module != ''"> AND module = #{module} </if>
              <if test="result != null and result != ''"> AND result = #{result} </if>
              <if test="begin != null"> AND created_at &gt;= #{begin} </if>
              <if test="end != null"> AND created_at &lt;= #{end} </if>
              <if test="keyword != null and keyword != ''">
                AND (username LIKE #{kw} OR target LIKE #{kw} OR detail LIKE #{kw})
              </if>
            </where>
            ORDER BY created_at DESC, id DESC
            <if test="limit != null"> LIMIT #{limit} OFFSET #{offset} </if>
            </script>
            """;

    @Select(FILTER)
    List<SysLog> selectLogs(@Param("kind") String kind, @Param("module") String module,
                            @Param("result") String result, @Param("keyword") String keyword,
                            @Param("kw") String kw,
                            @Param("begin") LocalDateTime begin, @Param("end") LocalDateTime end,
                            @Param("limit") Integer limit, @Param("offset") Integer offset);

    @Select("""
            <script>
            SELECT COUNT(1) FROM sys_operation_log
            <where>
              <if test="kind != null and kind != ''"> AND kind = #{kind} </if>
              <if test="module != null and module != ''"> AND module = #{module} </if>
              <if test="result != null and result != ''"> AND result = #{result} </if>
              <if test="begin != null"> AND created_at &gt;= #{begin} </if>
              <if test="end != null"> AND created_at &lt;= #{end} </if>
              <if test="keyword != null and keyword != ''">
                AND (username LIKE #{kw} OR target LIKE #{kw} OR detail LIKE #{kw})
              </if>
            </where>
            </script>
            """)
    long countLogs(@Param("kind") String kind, @Param("module") String module,
                   @Param("result") String result, @Param("keyword") String keyword,
                   @Param("kw") String kw,
                   @Param("begin") LocalDateTime begin, @Param("end") LocalDateTime end);

    @Insert("""
            INSERT INTO sys_operation_log
              (id, kind, username, user_id, module, action, target, detail,
               request_method, request_uri, ip, result, duration_ms, created_at)
            VALUES
              (#{id}, #{kind}, #{username}, #{userId}, #{module}, #{action}, #{target}, #{detail},
               #{requestMethod}, #{requestUri}, #{ip}, #{result}, #{durationMs}, #{now})
            """)
    int insert(@Param("id") String id, @Param("kind") String kind,
               @Param("username") String username, @Param("userId") String userId,
               @Param("module") String module, @Param("action") String action,
               @Param("target") String target, @Param("detail") String detail,
               @Param("requestMethod") String requestMethod, @Param("requestUri") String requestUri,
               @Param("ip") String ip, @Param("result") String result,
               @Param("durationMs") Integer durationMs, @Param("now") LocalDateTime now);
}
