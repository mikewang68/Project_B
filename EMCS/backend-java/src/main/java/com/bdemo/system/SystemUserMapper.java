package com.bdemo.system;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SystemUserMapper {
    @Select("""
            SELECT user_id, dept_id, user_name, nick_name, avatar, password, status, del_flag
            FROM sys_user
            WHERE user_name = #{userName} AND del_flag = '0'
            LIMIT 1
            """)
    Optional<UserAccount> findByUserName(@Param("userName") String userName);

    @Select("""
            SELECT user_id, dept_id, user_name, nick_name, avatar, password, status, del_flag
            FROM sys_user
            WHERE user_id = #{userId} AND del_flag = '0'
            """)
    Optional<UserAccount> findById(@Param("userId") long userId);

    @Select("""
            SELECT r.role_key
            FROM sys_role r
            JOIN sys_user_role ur ON ur.role_id = r.role_id
            WHERE ur.user_id = #{userId} AND r.status = '0' AND r.del_flag = '0'
            GROUP BY r.role_key
            ORDER BY MIN(r.role_sort)
            """)
    List<String> findRoleKeys(@Param("userId") long userId);

    @Select("""
            SELECT DISTINCT m.perms
            FROM sys_menu m
            JOIN sys_role_menu rm ON rm.menu_id = m.menu_id
            JOIN sys_user_role ur ON ur.role_id = rm.role_id
            WHERE ur.user_id = #{userId} AND m.status = '0'
              AND m.perms IS NOT NULL AND m.perms <> ''
            """)
    List<String> findPermissions(@Param("userId") long userId);

    @Update("UPDATE sys_user SET login_ip = #{ip}, login_date = NOW() WHERE user_id = #{userId}")
    int updateLogin(@Param("userId") long userId, @Param("ip") String ip);
}
