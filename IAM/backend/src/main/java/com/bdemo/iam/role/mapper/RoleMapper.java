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
    @Select("SELECT DISTINCT s.app_code FROM iam_role_app_scope s JOIN iam_role r ON r.id=s.role_id AND r.status='active' AND r.deleted=0 JOIN iam_user_role ur ON ur.role_id=r.id WHERE ur.user_id=#{userId} ORDER BY s.app_code")
    List<String> selectAllowedApps(@Param("userId") String userId);
    @Select("SELECT DISTINCT s.app_code FROM iam_role_app_scope s JOIN iam_role r ON r.id=s.role_id AND r.status='active' AND r.deleted=0 JOIN iam_user_role ur ON ur.role_id=r.id WHERE ur.user_id=#{userId} AND s.access_level='ADMIN' ORDER BY s.app_code")
    List<String> selectAdminApps(String userId);
    @Select("SELECT app_code FROM iam_role_app_scope WHERE role_id=#{id} ORDER BY app_code")
    List<String> selectRoleApps(String id);
    @Insert("INSERT INTO iam_role_app_scope(role_id,app_code) VALUES(#{id},#{app})")
    void insertAppScope(@Param("id") String id,@Param("app") String app);
    @Select("SELECT app_code FROM iam_role_app_scope WHERE role_id=#{id} AND access_level='ADMIN'")
    List<String> selectRoleAdminApps(String id);
    @Insert("INSERT INTO iam_role_app_scope(role_id,app_code,access_level) VALUES(#{id},#{app},#{level})")
    void insertAppScopeLevel(@Param("id") String id,@Param("app") String app,@Param("level") String level);
    @Delete("DELETE FROM iam_role_app_scope WHERE role_id=#{id}")
    void deleteAppScopes(String id);
    @Select("SELECT app_code FROM iam_management_owner WHERE object_kind=#{kind} AND object_id=#{id}")
    String selectManagementOwner(@Param("kind") String kind,@Param("id") String id);
    @Insert("INSERT INTO iam_management_owner(object_kind,object_id,app_code) VALUES(#{kind},#{id},#{app})")
    void insertManagementOwner(@Param("kind") String kind,@Param("id") String id,@Param("app") String app);
    @Select("SELECT system_code FROM iam_permission WHERE permission_code=#{code} AND status='active'")
    String selectPermissionSystem(String code);

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
            "INSERT INTO iam_role_permission(role_id, permission_id) SELECT #{roleId},p.id FROM iam_permission p WHERE p.permission_code IN " +
            "<foreach collection='permCodes' item='code' open='(' separator=',' close=')'>#{code}</foreach>" +
            "</script>")
    int insertRolePermissions(@Param("roleId") String roleId, @Param("permCodes") List<String> permCodes);

    @Select("SELECT p.permission_code FROM iam_role_permission rp JOIN iam_permission p ON p.id=rp.permission_id WHERE rp.role_id = #{roleId} ORDER BY p.permission_code")
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
            SELECT rp.role_id AS roleId, p.permission_code AS permissionCode
            FROM iam_role_permission rp JOIN iam_permission p ON p.id=rp.permission_id
            WHERE rp.role_id IN
            <foreach collection='roleIds' item='rid' open='(' separator=',' close=')'>#{rid}</foreach>
            </script>
            """)
    List<RolePermissionRow> selectRolePermissionRows(@Param("roleIds") List<String> roleIds);

    /** 全量角色-权限关系（权限健康规则批量加载）。 */
    @Select("SELECT rp.role_id AS roleId,p.permission_code AS permissionCode FROM iam_role_permission rp JOIN iam_permission p ON p.id=rp.permission_id")
    List<RolePermissionRow> selectAllRolePermissionRows();
}
