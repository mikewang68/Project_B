package com.bproject.ehm.asset.application;
import com.bproject.ehm.asset.domain.model.*;
import com.bproject.ehm.platform.persistence.opengauss.*;
import com.bproject.ehm.platform.security.*;
import com.bproject.iam.context.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:dossier-scope;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver",
 "spring.datasource.username=sa","spring.datasource.password=","ehm.persistence.auto-ddl=true","ehm.auth.mode=disabled","ehm.audit.endpoint=",
 "ehm.ai.enabled=false","ehm.sample-data.enabled=false","ehm.simulation.enabled=false","ehm.project.default-id=PROJECT_B","ehm.config.path=target/no-dossier-config.json"})
@ActiveProfiles("server")
class DossierScopeIntegrationTest {
 @Autowired DeviceDossierService service;@Autowired OpenGaussJsonStore store;@Autowired JdbcTemplate db;
 UserContext context(String region,boolean readOnly){var tuple=new ScopeTriple("PROJECT_B",region,"EHMS");var sql=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(tuple));return new UserContext(2,"fixture","fixture","fixture","fixture","L4_OPERATOR",readOnly,"EHMS","PROJECT_B",region,"EHMS",ScopeSql.Mode.SCOPED,List.of(tuple),List.of(tuple),Set.of(),Set.of("EHMS:asset-profile"),Map.of("EHMS:asset-profile",Set.of("VIEW")),Set.of(),"1",sql.my_info(),sql.my_project_zone_info(),sql.my_info_template(),sql.my_project_zone_info_template(),sql.scope_params());}
 @BeforeEach void seed()throws Exception{org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();db.update("DELETE FROM ehm.aggregate_store");for(String region:List.of("REGION_A","REGION_B"))try(var p=ProjectContext.bind("PROJECT_B");var scope=EhmCentralScope.bind(context(region,false))){store.save("asset","EQ-SAME",Asset.create("EQ-SAME","[虚拟]设备","测试","测试区",null,"测试组",Instant.now()));}}
 DeviceDossier request(DeviceDossier value,String manufacturer){return new DeviceDossier(value.assetCode(),manufacturer,"","","","","","",null,List.of(),value.version(),null);}
 @Test void dossierFactsRemainIsolatedAndStaleWritesFail()throws Exception{try(var p=ProjectContext.bind("PROJECT_B");var scope=EhmCentralScope.bind(context("REGION_A",false))){var empty=service.get("EQ-SAME");service.update("EQ-SAME",request(empty,"A厂家"));assertThrows(com.bproject.ehm.shared.error.DomainConflictException.class,()->service.update("EQ-SAME",request(empty,"覆盖")));assertEquals("A厂家",service.get("EQ-SAME").manufacturer());}try(var p=ProjectContext.bind("PROJECT_B");var scope=EhmCentralScope.bind(context("REGION_B",false))){assertEquals("",service.get("EQ-SAME").manufacturer());}}
 @Test void readOnlyCannotWriteAndNativeGrantDoesNotReplaceCentralAction()throws Exception{try(var p=ProjectContext.bind("PROJECT_B");var scope=EhmCentralScope.bind(context("REGION_A",true))){assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.update("EQ-SAME",request(service.get("EQ-SAME"),"拒绝")));}var c=context("REGION_A",false);var actor=new EhmSsoSessions.Actor("fixture","fixture","USER","S",false,Set.of("PROJECT_B"),Set.of("ehealth:asset:archive:view","ehealth:asset:archive:edit"),false,c);assertTrue(EhmBusinessPermissions.permits(actor,"GET","/api/ehm/v1/devices/EQ-SAME/dossier"));assertFalse(EhmBusinessPermissions.permits(actor,"PUT","/api/ehm/v1/devices/EQ-SAME/dossier"));assertFalse(EhmBusinessPermissions.permits(actor,"POST","/api/ehm/v1/devices/EQ-SAME/dossier/documents"));}
}
