package com.bdemo.system;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SystemMenuMapper {
    @Select("""
            <script>
            SELECT DISTINCT m.menu_id, m.menu_name, m.parent_id, m.order_num, m.path,
                   m.component, m.query, m.route_name, m.is_frame, m.is_cache,
                   m.menu_type, m.visible, m.icon
            FROM sys_menu m
            <if test='userId != 1'>
              JOIN sys_role_menu rm ON rm.menu_id = m.menu_id
              JOIN sys_user_role ur ON ur.role_id = rm.role_id AND ur.user_id = #{userId}
            </if>
            WHERE m.status = '0' AND m.menu_type IN ('M', 'C')
            ORDER BY m.parent_id, m.order_num, m.menu_id
            </script>
            """)
    List<MenuItem> findRouterMenus(@Param("userId") long userId);
}
