package com.bdemo.iam.navigation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bdemo.iam.common.BizException;
import com.bdemo.iam.log.OperationLogger;
import com.bdemo.iam.navigation.service.NavigationAddressPublicationService;
import com.bdemo.iam.navigation.service.NavigationAddressPublicationService.Address;
import com.bdemo.iam.security.LoginUser;
import com.bdemo.iam.sso.SsoServiceIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/** Real SQL and transaction checks; readiness is isolated here and exercised over HTTPS at deployment. */
@EnabledIfEnvironmentVariable(named="IAM_ADDRESS_DB_TEST",matches="1")
class NavigationAddressDatabaseTest {
  JdbcTemplate db;TransactionTemplate tx;OperationLogger audit;boolean rejectProbe;
  NavigationAddressPublicationService service;
  @BeforeEach void prepare(){
    String url=System.getenv("IAM_ADDRESS_DB_URL");
    assertNotNull(url);
    assertTrue(url.matches("jdbc:postgresql://[^/]+/codex_public_candidate_[a-z0-9_]+(?:\\?.*)?"),"Only task-owned candidate databases may be mutated");
    var ds=new DriverManagerDataSource(url,System.getenv("IAM_ADDRESS_DB_USER"),System.getenv("IAM_ADDRESS_DB_PASSWORD"));
    db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    assertTrue(db.queryForObject("SELECT current_database()",String.class).startsWith("codex_public_candidate_"));
    audit=mock(OperationLogger.class);rejectProbe=false;
    service=new NavigationAddressPublicationService(db,new ObjectMapper(),mock(SsoServiceIdentity.class),audit,""){
      @Override public void probe(String app,Address address){if(rejectProbe)throw new BizException(502,50201,"probe failure");}
    };
    login(true,false);
  }
  void login(boolean platform,boolean readonly){
    var user=new LoginUser("address-test","address-test","address-test",platform,Set.of());user.setReadOnly(readonly);
    user.setAdminAppCodes(Set.of("IAM"));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  long version(){return db.queryForObject("SELECT address_version FROM iam.iam_navigation_app WHERE app_code='WMS'",Long.class);}
  Address next(String path){
    var r=db.queryForMap("SELECT domain_name,public_scheme,public_port,origin_scheme,origin_ip,origin_port FROM iam.iam_navigation_app WHERE app_code='WMS'");
    return new Address((String)r.get("domain_name"),(String)r.get("public_scheme"),r.get("public_port")==null?null:((Number)r.get("public_port")).intValue(),
      (String)r.get("origin_scheme"),(String)r.get("origin_ip"),((Number)r.get("origin_port")).intValue(),path);
  }
  @Test void publishPinsInflightVersionAndRollbackCreatesANewVersion(){
    long before=version();String base=service.runtime("WMS",null).get("baseUrl").toString();
    tx.executeWithoutResult(status->{
      var draft=service.draft("WMS",before,next("/address-verification/"));String id=draft.get("revision_id").toString();
      assertEquals("DRAFT",draft.get("state"));assertEquals(before,version());
      assertEquals("VALIDATED",service.validate(id).get("state"));
      assertEquals(before+1,service.publish(id).get("version"));
      assertTrue(service.runtime("WMS",null).get("baseUrl").toString().endsWith("/address-verification/"));
      assertEquals(base,service.runtime("WMS",before).get("baseUrl"));
      String previous=db.queryForObject("SELECT revision_id FROM iam.iam_navigation_address_revision WHERE app_code='WMS' AND published_version=?",String.class,before);
      assertEquals(before+2,service.rollback(previous).get("version"));
      assertEquals(base,service.runtime("WMS",null).get("baseUrl"));
      db.update("UPDATE iam.iam_navigation_address_revision SET retire_after=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE app_code='WMS' AND published_version=?",before);
      assertThrows(BizException.class,()->service.runtime("WMS",before));
      status.setRollbackOnly();
    });
    assertEquals(before,version());assertEquals(base,service.runtime("WMS",null).get("baseUrl"));
  }
  @Test void failedValidationAndStaleDraftCannotModifyActiveConfiguration(){
    long before=version();tx.executeWithoutResult(status->{
      String failed=service.draft("WMS",before,next("/failed-probe/")).get("revision_id").toString();
      rejectProbe=true;assertEquals("FAILED",service.validate(failed).get("state"));
      assertThrows(BizException.class,()->service.publish(failed));assertEquals(before,version());
      rejectProbe=false;
      String stale=service.draft("WMS",before,next("/stale/")).get("revision_id").toString();service.validate(stale);
      String fresh=service.draft("WMS",before,next("/fresh/")).get("revision_id").toString();service.validate(fresh);service.publish(fresh);
      assertThrows(BizException.class,()->service.publish(stale));assertEquals(before+1,version());
      login(false,false);assertThrows(BizException.class,()->service.draft("WMS",before+1,next("/forbidden/")));
      login(true,true);assertThrows(BizException.class,()->service.rollback(fresh));
      status.setRollbackOnly();
    });assertEquals(before,version());
  }
}
