package com.bdemo.iam;

import static org.junit.jupiter.api.Assertions.*;
import com.bdemo.iam.insight.EffectivePermissionService;
import com.bdemo.iam.permission.PermissionService;
import com.bdemo.iam.permission.mapper.PermissionMapper;
import com.bdemo.iam.role.mapper.RoleMapper;
import com.bdemo.iam.user.UserService;
import com.bdemo.iam.user.mapper.UserMapper;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real mapper SQL + runtime authorization + explanation; all fixture changes are rolled back. */
@EnabledIfEnvironmentVariable(named="IAM_IDENTITY_DB_TEST", matches="1")
class PermissionAuthorizationDatabaseTest {
  @Test
  void explanationAgreesWithRuntimeForActiveDisabledDeletedAndSuperAdmin() throws Exception {
    String url=System.getenv("IAM_IDENTITY_DB_URL");
    assertNotNull(url);
    String prefix="jdbc:postgresql://127.0.0.1:25432/iam_identity_",suffix="_test?currentSchema=iam";
    assertTrue(url.startsWith(prefix)&&url.endsWith(suffix),"Only an isolated identity test database is allowed");
    String fixture=url.substring(prefix.length(),url.length()-suffix.length());
    assertTrue(!fixture.isEmpty()&&fixture.chars().allMatch(c->c>='a'&&c<='z'||c>='0'&&c<='9'||c=='_'));
    var ds=new DriverManagerDataSource(url,System.getenv("IAM_IDENTITY_DB_USER"),System.getenv("IAM_IDENTITY_DB_PASSWORD"));
    var config=new Configuration(new Environment("isolated-test",new JdbcTransactionFactory(),ds));
    config.setMapUnderscoreToCamelCase(true);
    config.setLocalCacheScope(LocalCacheScope.STATEMENT);
    config.addMapper(UserMapper.class);config.addMapper(RoleMapper.class);config.addMapper(PermissionMapper.class);
    try(var session=new SqlSessionFactoryBuilder().build(config).openSession(false)) {
      String id=UUID.randomUUID().toString().replace("-", ""), role=UUID.randomUUID().toString().replace("-", "");
      var db=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(session.getConnection(),true));
      try {
        db.update("INSERT INTO iam.iam_user(id,username,password_hash,display_name) VALUES(?,?,?,'permission fixture')",id,"perm-"+id,"UNUSABLE_TEST_HASH");
        db.update("INSERT INTO iam.iam_role(id,role_code,role_name) VALUES(?,?,'permission fixture')",role,"fixture-"+role);
        db.update("INSERT INTO iam.iam_user_role(user_id,role_id) VALUES(?,?)",id,role);
        // A permission code and its storage ID are separate. An active role also
        // needs an explicit application boundary, as every runtime mapper does.
        assertEquals(1,db.update("INSERT INTO iam.iam_role_permission(role_id,permission_id)"
            +" SELECT ?,id FROM iam.iam_permission WHERE permission_code='iam:user:list:view' AND system_code='iam'",role));
        db.update("INSERT INTO iam.iam_role_app_scope(role_id,app_code) VALUES(?,'IAM')",role);
        var users=session.getMapper(UserMapper.class);var roles=session.getMapper(RoleMapper.class);var perms=session.getMapper(PermissionMapper.class);
        var runtime=new UserService(users,roles,null,new PermissionService(perms),null);
        var insight=new EffectivePermissionService(users,roles,perms,runtime);
        assertTrue(runtime.load(id).hasPermission("iam:user:list:view"));
        assertTrue(insight.explain(id,"iam:user:list:view").isOwned());
        assertEquals(role,insight.getView(id).getPermissions().get(0).getSourceRoles().get(0).getId());
        db.update("UPDATE iam.iam_user SET status='disabled' WHERE id=?",id);
        assertNull(runtime.load(id));assertFalse(insight.explain(id,"iam:user:list:view").isOwned());
        db.update("UPDATE iam.iam_user SET status='active' WHERE id=?",id);
        db.update("UPDATE iam.iam_role SET status='inactive' WHERE id=?",role);
        assertFalse(runtime.load(id).hasPermission("iam:user:list:view"));assertFalse(insight.explain(id,"iam:user:list:view").isOwned());
        db.update("UPDATE iam.iam_role SET status='active',deleted=1 WHERE id=?",role);
        assertFalse(runtime.load(id).hasPermission("iam:user:list:view"));assertFalse(insight.explain(id,"iam:user:list:view").isOwned());
        var admin=db.queryForObject("SELECT id FROM iam.iam_role WHERE role_code='super_admin' AND status='active' AND deleted=0",String.class);
        db.update("INSERT INTO iam.iam_user_role(user_id,role_id) VALUES(?,?)",id,admin);
        assertTrue(runtime.load(id).hasPermission("uncatalogued:operation"));assertTrue(insight.explain(id,"uncatalogued:operation").isOwned());
        assertTrue(insight.getView(id).isSuperAdmin());
      } finally {session.rollback(true);}
    }
  }
}
