package com.bdemo.sys.identity.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 跨 schema 只读访问 IAM 库（sys_app 已被授予 iam 六表 SELECT）。
 */
@Mapper
public interface IamIdentityMapper {

    @Select("""
            SELECT id, username, display_name AS displayName, status
            FROM iam.iam_user
            WHERE id = #{userId} AND deleted = 0
            """)
    Map<String, Object> selectUser(@Param("userId") String userId);

    @Select("""
            SELECT r.id, r.role_code AS code, r.role_name AS name
            FROM iam.iam_user_role ur
            JOIN iam.iam_role r ON r.id = ur.role_id AND r.deleted = 0 AND r.status = 'active'
            WHERE ur.user_id = #{userId}
            """)
    List<Map<String, Object>> selectRoles(@Param("userId") String userId);

    @Select("""
            SELECT DISTINCT rp.permission_id AS code
            FROM iam.iam_user_role ur
            JOIN iam.iam_role r ON r.id = ur.role_id AND r.deleted = 0 AND r.status = 'active'
            JOIN iam.iam_role_permission rp ON rp.role_id = r.id
            WHERE ur.user_id = #{userId}
            """)
    List<String> selectPermissionCodes(@Param("userId") String userId);
}
