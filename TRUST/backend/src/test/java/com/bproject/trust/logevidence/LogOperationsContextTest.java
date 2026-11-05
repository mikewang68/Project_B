package com.bproject.trust.logevidence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bproject.iam.context.*;
import com.bproject.trust.identity.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

class LogOperationsContextTest {
  UserContext context(String level,boolean readonly,ScopeSql.Mode mode,Set<String> actions){
    var tuples=mode==ScopeSql.Mode.GLOBAL?List.<ScopeTriple>of():List.of(new ScopeTriple("PROJECT_B","SHEN_TIE","TRUST"));var f=ScopeSql.build(mode,tuples);
    return new UserContext(2,"subject","user","subject","user",level,readonly,"TRUST",null,null,null,mode,tuples,tuples,Set.of("role"),Set.of("TRUST:status"),Map.of("TRUST:status",actions),Set.of(),"1",f.my_info(),f.my_project_zone_info(),f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
  }
  @Test void globalReadonlyCanReadHistoryAndOnlyItsOrganizationDelivery() throws Exception {
    var db=mock(JdbcTemplate.class);var settings=mock(LogSettings.class);var tx=mock(TransactionTemplate.class);
    when(settings.read()).thenReturn(new LogSettings.Config(1,true,"test","",Set.of(),List.of(),new LogSettings.Delivery(false,"","",Map.of())));
    var ops=new LogOperations(db,tx,settings);var auth=new UsernamePasswordAuthenticationToken(new PlatformPrincipal("subject","user","ORG"),null,List.of());
    try(var scope=TrustCentralScope.bind(context("L1_READONLY",true,ScopeSql.Mode.GLOBAL,Set.of("VIEW")))){
      assertDoesNotThrow(()->ops.requireSettingsHistory(auth));assertDoesNotThrow(()->ops.deliveries(auth));
      verify(db).queryForList("SELECT id,action,outcome,state,attempts,last_error,created_at FROM audit_delivery_read WHERE state<>'DELIVERED' AND org_id=? ORDER BY created_at LIMIT 200","ORG");
      assertThrows(com.bproject.trust.ingress.IngressError.class,()->ops.retryDelivery(auth,"00000000-0000-0000-0000-000000000001",new LogOperations.RetryDelivery("test")));
      verifyNoInteractions(tx);
    }
  }
  @Test void RegionalAndUnregisteredContextsDoNotGainGlobalHistory() throws Exception {
    var settings=mock(LogSettings.class);when(settings.read()).thenReturn(new LogSettings.Config(1,true,"test","",Set.of(),List.of(),new LogSettings.Delivery(false,"","",Map.of())));
    var ops=new LogOperations(mock(JdbcTemplate.class),mock(TransactionTemplate.class),settings);var auth=new UsernamePasswordAuthenticationToken(new PlatformPrincipal("subject","user","ORG"),null,List.of());
    for(var c:List.of(context("L4_OPERATOR",false,ScopeSql.Mode.SCOPED,Set.of("VIEW")),context("L1_SUPER",false,ScopeSql.Mode.GLOBAL,Set.of("EDIT")))){
      try(var scope=TrustCentralScope.bind(c)){assertThrows(com.bproject.trust.ingress.IngressError.class,()->ops.requireSettingsHistory(auth));assertThrows(com.bproject.trust.ingress.IngressError.class,()->ops.deliveries(auth));}
    }
  }
}
