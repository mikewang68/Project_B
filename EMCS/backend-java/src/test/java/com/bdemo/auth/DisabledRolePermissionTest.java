package com.bdemo.auth;
import com.bdemo.system.SystemUserMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class DisabledRolePermissionTest {
 @Test void disabledOrDeletedRoleCannotKeepItsPermissions() throws Exception {
  var ds=new DriverManagerDataSource("jdbc:h2:mem:role_scope;DB_CLOSE_DELAY=-1","sa","");var j=new JdbcTemplate(ds);
  j.execute("CREATE TABLE sys_menu(menu_id bigint,status varchar,perms varchar)");
  j.execute("CREATE TABLE sys_role(role_id bigint,status varchar,del_flag varchar)");
  j.execute("CREATE TABLE sys_role_menu(role_id bigint,menu_id bigint)");
  j.execute("CREATE TABLE sys_user_role(user_id bigint,role_id bigint)");
  j.update("INSERT INTO sys_menu VALUES(1,'0','energy:overview:view')");j.update("INSERT INTO sys_role VALUES(1,'0','0')");
  j.update("INSERT INTO sys_role_menu VALUES(1,1)");j.update("INSERT INTO sys_user_role VALUES(42,1)");
  var f=new SqlSessionFactoryBean();f.setDataSource(ds);var config=new org.apache.ibatis.session.Configuration();config.addMapper(SystemUserMapper.class);f.setConfiguration(config);
  try(var s=f.getObject().openSession(true)){
   var users=s.getMapper(SystemUserMapper.class);assertEquals(1,users.findPermissions(42).size());
   j.update("UPDATE sys_role SET status='1'");s.clearCache();assertTrue(users.findPermissions(42).isEmpty());
   j.update("UPDATE sys_role SET status='0',del_flag='2'");s.clearCache();assertTrue(users.findPermissions(42).isEmpty());
  }
 }
}
