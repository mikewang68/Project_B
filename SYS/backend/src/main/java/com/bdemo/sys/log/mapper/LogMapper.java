package com.bdemo.sys.log.mapper;

import com.bdemo.sys.log.domain.SysLog;
import com.bdemo.sys.log.dto.StatRow;
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

    // ============ 统计聚合（统一过滤条件） ============
    String STATS_WHERE = """
            <where>
              <if test="begin != null"> AND created_at &gt;= #{begin} </if>
              <if test="end != null"> AND created_at &lt;= #{end} </if>
              <if test="user != null and user != ''"> AND username = #{user} </if>
              <if test="module != null and module != ''"> AND module = #{module} </if>
              <if test="result != null and result != ''"> AND result = #{result} </if>
            </where>
            """;

    @Select("""
            <script>
            SELECT result AS name, COUNT(1) AS total,
                   SUM(CASE WHEN result = 'fail' THEN 1 ELSE 0 END) AS fail
            FROM sys_operation_log
            """ + STATS_WHERE + """
            GROUP BY result
            </script>
            """)
    List<StatRow> groupByResult(@Param("begin") LocalDateTime begin, @Param("end") LocalDateTime end,
                                @Param("user") String user, @Param("module") String module,
                                @Param("result") String result);

    @Select("""
            <script>
            SELECT module AS name, COUNT(1) AS total,
                   SUM(CASE WHEN result = 'fail' THEN 1 ELSE 0 END) AS fail
            FROM sys_operation_log
            """ + STATS_WHERE + """
            GROUP BY module
            ORDER BY total DESC
            </script>
            """)
    List<StatRow> groupByModule(@Param("begin") LocalDateTime begin, @Param("end") LocalDateTime end,
                                @Param("user") String user, @Param("module") String module,
                                @Param("result") String result);

    @Select("""
            <script>
            SELECT username AS name, COUNT(1) AS total,
                   SUM(CASE WHEN result = 'fail' THEN 1 ELSE 0 END) AS fail
            FROM sys_operation_log
            """ + STATS_WHERE + """
            GROUP BY username
            ORDER BY total DESC
            </script>
            """)
    List<StatRow> groupByUser(@Param("begin") LocalDateTime begin, @Param("end") LocalDateTime end,
                              @Param("user") String user, @Param("module") String module,
                              @Param("result") String result);

    @Select("""
            <script>
            SELECT id, kind, username, module, action, target, detail, ip, result,
                   created_at AS createdAt
            FROM sys_operation_log
            """ + STATS_WHERE + """
            ORDER BY created_at ASC, id ASC
            </script>
            """)
    List<SysLog> selectForDiagnosis(@Param("begin") LocalDateTime begin, @Param("end") LocalDateTime end,
                                    @Param("user") String user, @Param("module") String module,
                                    @Param("result") String result);
}
