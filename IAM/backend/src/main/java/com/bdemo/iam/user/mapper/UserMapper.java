package com.bdemo.iam.user.mapper;

import com.bdemo.iam.user.domain.User;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserMapper {

    String COLUMNS = """
            id, username, display_name AS name, phone, email, status,
            zone, company, dept, group_name AS "group",
            zone_code, company_code, dept_code, group_code, org_path AS orgPath,
            blockchain_id AS blockchainId, blockchain_address AS blockchainAddress,
            last_login_at AS lastLoginAt, created_at AS createdAt, updated_at AS updatedAt
            """;

    @Select("SELECT " + COLUMNS + " FROM iam_user WHERE id = #{id} AND deleted = 0")
    User selectById(@Param("id") String id);

    @Select("SELECT id, username, display_name AS name, status, password_hash AS passwordHash " +
            "FROM iam_user WHERE username = #{username} AND deleted = 0")
    java.util.Map<String, Object> selectAuthByUsername(@Param("username") String username);

    @Select("""
            <script>
            SELECT ${cols} FROM iam_user
            WHERE deleted = 0
            <if test="status != null and status != ''"> AND status = #{status} </if>
            <if test="keyword != null and keyword != ''">
              AND (lower(username) LIKE lower(#{kw}) OR lower(display_name) LIKE lower(#{kw})
                   OR lower(coalesce(phone,'')) LIKE lower(#{kw})
                   OR lower(coalesce(zone,'')) LIKE lower(#{kw})
                   OR lower(coalesce(company,'')) LIKE lower(#{kw})
                   OR lower(coalesce(dept,'')) LIKE lower(#{kw})
                   OR lower(coalesce(group_name,'')) LIKE lower(#{kw}))
            </if>
            ORDER BY created_at ASC
            </script>
            """)
    List<User> selectList(@Param("cols") String cols,
                          @Param("status") String status,
                          @Param("keyword") String keyword,
                          @Param("kw") String kw);

    @Select("SELECT " + COLUMNS + " FROM iam_user WHERE deleted = 0 ORDER BY created_at ASC")
    List<User> selectAll();

    @Insert("""
            INSERT INTO iam_user
              (id, username, password_hash, display_name, phone, email, status,
               zone, company, dept, group_name, zone_code, company_code, dept_code, group_code,
               org_path, blockchain_id, blockchain_address, created_at, updated_at, deleted)
            VALUES
              (#{id}, #{username}, #{passwordHash}, #{name}, #{phone}, #{email}, #{status},
               #{zone}, #{company}, #{dept}, #{group}, #{zoneCode}, #{companyCode}, #{deptCode}, #{groupCode},
               #{orgPath}, #{blockchainId}, #{blockchainAddress}, #{now}, #{now}, 0)
            """)
    int insert(@Param("id") String id,
               @Param("username") String username,
               @Param("passwordHash") String passwordHash,
               @Param("name") String name,
               @Param("phone") String phone,
               @Param("email") String email,
               @Param("status") String status,
               @Param("zone") String zone,
               @Param("company") String company,
               @Param("dept") String dept,
               @Param("group") String group,
               @Param("zoneCode") String zoneCode,
               @Param("companyCode") String companyCode,
               @Param("deptCode") String deptCode,
               @Param("groupCode") String groupCode,
               @Param("orgPath") String orgPath,
               @Param("blockchainId") String blockchainId,
               @Param("blockchainAddress") String blockchainAddress,
               @Param("now") LocalDateTime now);

    @Update("""
            <script>
            UPDATE iam_user
            <set>
              display_name = #{name},
              phone = #{phone},
              email = #{email},
              status = #{status},
              zone = #{zone},
              company = #{company},
              dept = #{dept},
              group_name = #{group},
              zone_code = #{zoneCode},
              company_code = #{companyCode},
              dept_code = #{deptCode},
              group_code = #{groupCode},
              org_path = #{orgPath},
              blockchain_id = #{blockchainId},
              blockchain_address = #{blockchainAddress},
              <if test="passwordHash != null and passwordHash != ''"> password_hash = #{passwordHash}, </if>
              updated_at = #{now}
            </set>
            WHERE id = #{id} AND deleted = 0
            </script>
            """)
    int update(@Param("id") String id,
               @Param("name") String name,
               @Param("phone") String phone,
               @Param("email") String email,
               @Param("status") String status,
               @Param("zone") String zone,
               @Param("company") String company,
               @Param("dept") String dept,
               @Param("group") String group,
               @Param("zoneCode") String zoneCode,
               @Param("companyCode") String companyCode,
               @Param("deptCode") String deptCode,
               @Param("groupCode") String groupCode,
               @Param("orgPath") String orgPath,
               @Param("blockchainId") String blockchainId,
               @Param("blockchainAddress") String blockchainAddress,
               @Param("passwordHash") String passwordHash,
               @Param("now") LocalDateTime now);

    @Update("UPDATE iam_user SET status = #{status}, updated_at = #{now} WHERE id = #{id} AND deleted = 0")
    int updateStatus(@Param("id") String id, @Param("status") String status, @Param("now") LocalDateTime now);

    @Update("UPDATE iam_user SET password_hash = #{passwordHash}, updated_at = #{now} WHERE id = #{id} AND deleted = 0")
    int updatePassword(@Param("id") String id, @Param("passwordHash") String passwordHash, @Param("now") LocalDateTime now);

    @Update("UPDATE iam_user SET deleted = 1, updated_at = #{now} WHERE id = #{id}")
    int logicDelete(@Param("id") String id, @Param("now") LocalDateTime now);

    @Update("UPDATE iam_user SET last_login_at = #{now} WHERE id = #{id}")
    int updateLastLogin(@Param("id") String id, @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(1) FROM iam_user WHERE username = #{username} AND deleted = 0")
    int countByUsername(@Param("username") String username);

    // ---- 用户-角色关系 ----

    @Delete("DELETE FROM iam_user_role WHERE user_id = #{userId}")
    int deleteUserRoles(@Param("userId") String userId);

    @Insert("<script>" +
            "INSERT INTO iam_user_role(user_id, role_id) VALUES " +
            "<foreach collection='roleIds' item='rid' separator=','>(#{userId}, #{rid})</foreach>" +
            "</script>")
    int insertUserRoles(@Param("userId") String userId, @Param("roleIds") List<String> roleIds);

    @Select("SELECT role_id FROM iam_user_role WHERE user_id = #{userId} ORDER BY role_id")
    List<String> selectRoleIds(@Param("userId") String userId);

    @Select("SELECT user_id FROM iam_user_role WHERE role_id = #{roleId}")
    List<String> selectUserIdsByRole(@Param("roleId") String roleId);
}
