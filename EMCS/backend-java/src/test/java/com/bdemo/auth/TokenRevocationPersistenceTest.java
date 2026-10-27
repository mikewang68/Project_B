package com.bdemo.auth;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class TokenRevocationPersistenceTest {
 @Test void migrationIsRepeatableAndLogoutSurvivesServiceRecreation() throws Exception {
  var ds=new DriverManagerDataSource("jdbc:h2:mem:revocation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
  try(var c=ds.getConnection()){
   var script=new FileSystemResource(Path.of("../sql/026_token_revocations.sql"));
   ScriptUtils.executeSqlScript(c,script);ScriptUtils.executeSqlScript(c,script);
  }
  var jdbc=new JdbcTemplate(ds);var first=service(jdbc);
  String token=first.create(42,"operator"),other=first.create(42,"operator");
  assertEquals(42,first.parse(token).userId());first.revoke(token);first.revoke(token);
  var restarted=service(jdbc);
  assertThrows(IllegalArgumentException.class,()->restarted.parse(token));
  assertEquals(42,restarted.parse(other).userId());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_token_revocation",Integer.class));
  assertNotEquals(token,jdbc.queryForObject("SELECT token_sha256 FROM sys_token_revocation",String.class));
 }
 private JwtService service(JdbcTemplate jdbc){
  var service=new JwtService("test-only-secret-with-at-least-thirty-two-bytes",5);
  ReflectionTestUtils.setField(service,"revocationDb",jdbc);return service;
 }
}
