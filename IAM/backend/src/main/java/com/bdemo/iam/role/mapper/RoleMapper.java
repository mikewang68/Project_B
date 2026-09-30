package com.bdemo.iam.role.mapper;

import com.bdemo.iam.role.domain.Role;
import com.bdemo.iam.role.dto.RoleBrief;
import com.bdemo.iam.role.dto.RolePermissionRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RoleMapper {

    String COLUMNS = """
            id, role_name AS name, role_code AS code, description, status,
            created_at AS createdAt, updated_at AS updatedAt
            """;

    @Select("SELECT " + COLUMNS + " FROM iam_role WHERE id = #{id} AND deleted = 0")
    Role selectById(@Param("id") String id);

    @Select("SELECT " + COLUMNS + " FROM iam_role WHERE deleted = 0 ORDER BY created_at ASC")
    List<Role> selectAll();

    @Insert("""
            INSERT INTO iam_role (id, role_code, role_name, description, status, created_at, updated_at, deleted)
            VALUES (#{id}, #{code}, #{name}, #{description}, #{status}, #{now}, #{now}, 0)
            """)
    int insert(@Param("id") String id,
               @Param("code") String code,
               @Param("name") String name,
               @Param("description") String description,
               @Param("status") String status,
               @Param("now") LocalDateTime now);

    @Update("""
            UPDATE iam_role
            SET role_name = #{name}, description = #{description}, status = #{status}, updated_at = #{now}
            WHERE id = #{id} AND deleted = 0
            """)
    int update(@Param("id") String id,
               @Param("name") String name,
               @Param("description") String description,
               @Param("status") String status,
               @Param("now") LocalDateTime now);

    @Update("UPDATE iam_role SET status = #{status}, updated_at = #{now} WHERE id = #{id} AND deleted = 0")
    int updateStatus(@Param("id") String id, @Param("status") String status, @Param("now") LocalDateTime now);

    @Update("UPDATE iam_role SET deleted = 1, updated_at = #{now} WHERE id = #{id}")
    int logicDelete(@Param("id") String id, @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(1) FROM iam_role WHERE role_code = #{code} AND deleted = 0")
    int countByCode(@Param("code") String code);

    @Select("SELECT role_code FROM iam_role WHERE id = #{id} AND deleted = 0")
    String selectCodeById(@Param("id") String id);

    // ---- 角色-权限关系 ----

    @Delete("DELETE FROM iam_role_permission WHERE role_id = #{roleId}")
    int deleteRolePermissions(@Param("roleId") String roleId);

    @Insert("<script>" +
            "INSERT INTO iam_role_permission(role_id, permission_id) VALUES " +
            "<foreach collection='permCodes' item='code' separator=','>(#{roleId}, #{code})</foreach>" +
            "</script>")
    int insertRolePermissions(@Param("roleId") String roleId, @Param("permCodes") List<String> permCodes);

    @Select("SELECT permission_id FROM iam_role_permission WHERE role_id = #{roleId} ORDER BY permission_id")
    List<String> selectPermissionCodes(@Param("roleId") String roleId);

    // ---- 有效权限洞察 ----

    /** 用户的有效（active、未删除）角色简要信息。 */
    @Select("""
            SELECT r.id AS id, r.role_code AS code, r.role_name AS name
            FROM iam_user_role ur
            JOIN iam_role r ON r.id = ur.role_id AND r.deleted = 0 AND r.status = 'active'
            WHERE ur.user_id = #{userId}
            ORDER BY r.created_at ASC, r.id ASC
            """)
    List<RoleBrief> selectActiveRolesByUser(@Param("userId") String userId);

    /** 批量取角色-权限关系行（供权限来源映射，避免 N+1）。 */
    @Select("""
            <script>
            SELECT role_id AS roleId, permission_id AS permissionCode
            FROM iam_role_permission
            WHERE role_id IN
            <foreach collection='roleIds' item='rid' open='(' separator=',' close=')'>#{rid}</foreach>
            </script>
            """)
    List<RolePermissionRow> selectRolePermissionRows(@Param("roleIds") List<String> roleIds);

    /** 全量角色-权限关系（权限健康规则批量加载）。 */
    @Select("SELECT role_id AS roleId, permission_id AS permissionCode FROM iam_role_permission")
    List<RolePermissionRow> selectAllRolePermissionRows();
}
