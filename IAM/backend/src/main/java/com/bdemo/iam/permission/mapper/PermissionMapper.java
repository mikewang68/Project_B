package com.bdemo.iam.permission.mapper;

import com.bdemo.iam.permission.domain.PermissionNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PermissionMapper {

    String NODE_COLUMNS = """
            id, parent_id AS parentId, node_type AS nodeType, permission_code AS code,
            permission_name AS name, system_code AS system, route, icon,
            sort_order AS sortOrder, status
            """;

    @Select("SELECT " + NODE_COLUMNS + " FROM iam_permission ORDER BY sort_order ASC, id ASC")
    List<PermissionNode> selectAllNodes();

    @Select("SELECT " + NODE_COLUMNS + " FROM iam_permission WHERE node_type = 'BUTTON' ORDER BY sort_order ASC, id ASC")
    List<PermissionNode> selectAllButtons();

    @Select("""
            <script>
            SELECT DISTINCT p.permission_code
            FROM iam_role_permission rp
            JOIN iam_role r ON r.id = rp.role_id AND r.deleted = 0 AND r.status = 'active'
            JOIN iam_permission p ON p.id = rp.permission_id AND p.status = 'active'
            WHERE p.permission_code IS NOT NULL AND p.permission_code &lt;&gt; '' AND rp.role_id IN
            <foreach collection='roleIds' item='rid' open='(' separator=',' close=')'>#{rid}</foreach>
            </script>
            """)
    List<String> selectCodesByRoleIds(@Param("roleIds") List<String> roleIds);
}
