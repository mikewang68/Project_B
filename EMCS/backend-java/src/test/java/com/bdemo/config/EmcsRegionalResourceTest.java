package com.bdemo.config;

import com.bdemo.auth.*;
import com.bdemo.common.BusinessException;
import com.bdemo.energy.alert.*;
import com.bdemo.energy.quality.*;
import com.bdemo.energy.suggestion.*;
import com.bdemo.energy.collection.CollectionService;
import com.bdemo.energy.agent.AgentService;
import com.bdemo.energy.agent.CloudLlmClient;
import com.bdemo.energy.cost.CostService;
import com.bproject.iam.context.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

/** Small in-memory fixtures only; no application properties or external database connection. */
class EmcsRegionalResourceTest {
    JdbcTemplate jdbc;
    final ObjectMapper mapper=new ObjectMapper();
    final LocalDateTime now=LocalDateTime.of(2026,7,6,18,0);
    static UserContext context(boolean readonly,String menu,Set<String> actions){
        var t=new ScopeTriple("PROJECT_B","SHEN_TIE","EMCS");var f=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(t));
        return new UserContext(2,"subject","operator","subject","operator","L4_OPERATOR",readonly,"EMCS","PROJECT_B","SHEN_TIE","EMCS",ScopeSql.Mode.SCOPED,
            List.of(t),List.of(t),Set.of("role"),Set.of(menu),Map.of(menu,actions),Set.of(),"1",f.my_info(),f.my_project_zone_info(),f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
    }
    @BeforeEach void setup(){
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:regional-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE e_area(area_id bigint PRIMARY KEY,area_code varchar,area_name varchar,project_id varchar,region_id varchar,subsystem_code varchar)");
        jdbc.execute("INSERT INTO e_area VALUES(1,'AREA-A','A','PROJECT_B','SHEN_TIE','EMCS'),(2,'AREA-B','B','PROJECT_B','SHEN_TIE','EMCS'),(3,'FOREIGN','Foreign','PROJECT_B','HA_TIE','EMCS'),(4,'LEGACY','Legacy','LEGACY','UNASSIGNED','EMCS')");
        jdbc.execute("CREATE TABLE e_equipment(equipment_id bigint PRIMARY KEY,equipment_code varchar,equipment_name varchar,area_id bigint)");
        jdbc.execute("CREATE TABLE e_meter_point(point_id bigint PRIMARY KEY,point_code varchar,area_id bigint,equipment_id bigint)");
        jdbc.execute("INSERT INTO e_meter_point VALUES(1,'OWN',1,NULL),(3,'FOREIGN',3,NULL),(4,'LEGACY',4,NULL)");
        jdbc.execute("CREATE TABLE e_alert_event(event_id bigint PRIMARY KEY,area_id bigint,status varchar)");
        jdbc.execute("INSERT INTO e_alert_event VALUES(1,1,'new'),(3,3,'new'),(4,4,'new')");
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void centralRegionAndNativeDispatchAreaIntersect() throws Exception {
        try(var scope=EmcsCentralScope.bind(context(false,"EMCS:2001",Set.of("VIEW")))){
            assertEquals(List.of(1L,2L),jdbc.queryForList("SELECT area_id FROM e_area WHERE 1=1"+EmcsCentralScope.area("area_id")+" ORDER BY area_id",Long.class));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("dispatch","unused",List.of(new SimpleGrantedAuthority("ROLE_dispatch"))));
            assertEquals(List.of(1L),jdbc.queryForList("SELECT area_id FROM e_area WHERE 1=1"+EmcsCentralScope.area("area_id"),Long.class));
        }
        assertNull(EmcsCentralScope.current());
    }
    @Test void foreignAndUnassignedPointCannotBeBackfilledOrSelected() throws Exception {
        var quality=new RawQualityService(jdbc,mapper,now);
        try(var scope=EmcsCentralScope.bind(context(false,"EMCS:2008",Set.of("VIEW","EDIT")))){
            for(String code:List.of("FOREIGN","LEGACY")){
                assertEquals(404,assertThrows(BusinessException.class,()->quality.summary(code,null,null,"ALL")).status());
                assertEquals(404,assertThrows(BusinessException.class,()->quality.backfill(new RawQualityController.BackfillRequest(code,"2026-07-06 06:00:00","2026-07-06 07:00:00",false),"operator")).status());
            }
        }
    }
    @Test void alertLockAndMutationLookupRejectsForeignIdBeforeAnyWrite() throws Exception {
        var alerts=new AlertService(jdbc,mapper,now);var body=new AlertController.TransitionRequest("not-a-status",null,"probe",null,null);
        try(var scope=EmcsCentralScope.bind(context(false,"EMCS:2010",Set.of("EDIT")))){
            assertEquals(404,assertThrows(BusinessException.class,()->alerts.transition(3,body,"operator")).status());
            assertEquals(404,assertThrows(BusinessException.class,()->alerts.transition(4,body,"operator")).status());
            assertEquals(409,assertThrows(BusinessException.class,()->alerts.transition(1,body,"operator")).status());
        }
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM e_alert_event WHERE status='new'",Integer.class));
    }
    @Test void suggestionCannotForgeAreaOrCreateGlobalTemplates() throws Exception {
        var suggestions=new SuggestionService(jdbc,mapper,now);
        try(var scope=EmcsCentralScope.bind(context(false,"EMCS:2011",Set.of("ADD","EDIT")))){
            var foreign=new HashMap<String,Object>(Map.of("title","SIM","measureContent","SIM","objectType","area","objectId",3,"areaId",1));
            assertEquals(403,assertThrows(BusinessException.class,()->suggestions.create(foreign,"operator")).status());
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->EmcsCentralScope.requireGlobalManagement());
        }
    }
    @Test void confirmationAndFlowEditAreDistinctForV2EvenWithNativeAdmin() {
        for(String action:List.of("CONFIRM","EDIT")){
            var current=new EmcsSsoSessions.Current(new AuthenticatedUser(42,"admin"),Set.of("admin"),Set.of("*:*:*"),false,Set.of(),context(false,"EMCS:2010",Set.of(action)));
            assertEquals(action.equals("CONFIRM"),EmcsAlertPermissions.permits(current,"ack"));
            assertEquals(action.equals("EDIT"),EmcsAlertPermissions.permits(current,"dispatched"));
        }
    }
    @Test void directDispatchCannotBypassConfirmationThroughImplicitAck() throws Exception {
        var alerts=new AlertService(jdbc,mapper,now);
        try(var scope=EmcsCentralScope.bind(context(false,"EMCS:2010",Set.of("EDIT")))){
            var body=new AlertController.TransitionRequest("dispatched","SIM","SIM",null,null);
            assertEquals(403,assertThrows(BusinessException.class,()->alerts.transition(1,body,"operator")).status());
            assertEquals("new",jdbc.queryForObject("SELECT status FROM e_alert_event WHERE event_id=1",String.class));
        }
    }
    @Test void exactResourceActionsAndReadonlyVetoAreRequired(){
        var c=context(false,"EMCS:2008",Set.of("VIEW","EDIT"));
        assertTrue(EmcsCentralScope.permits(c,"GET","/raw-quality/summary"));
        assertTrue(EmcsCentralScope.permits(c,"POST","/raw-quality/backfill"));
        assertFalse(EmcsCentralScope.permits(c,"POST","/raw-quality/recompute"));
        assertFalse(EmcsCentralScope.permits(c,"GET","/overview/summary"));
        assertFalse(EmcsCentralScope.permits(c,"POST","/raw-quality/arbitrary"));
        assertFalse(EmcsCentralScope.permits(context(true,"EMCS:2008",Set.of("EDIT","CONFIRM")),"POST","/raw-quality/backfill"));
    }
    @Test void deviceEventListAndDetailUseRegisteredAreaOwnership() throws Exception {
        jdbc.execute("CREATE TABLE emcs_collect_device(device_id varchar,area_code varchar,equipment_code varchar)");
        jdbc.execute("CREATE TABLE emcs_collect_event(event_id varchar,device_id varchar,observed_ts_ms bigint,source_ts_ms bigint,received_ts_ms bigint,simulated integer,point_count integer,wire_sha256 varchar,wire_json varchar)");
        jdbc.execute("CREATE TABLE emcs_collect_reading(event_id varchar,point_code varchar,value_json varchar,unit varchar,quality_code varchar)");
        jdbc.execute("INSERT INTO emcs_collect_device VALUES('own','AREA-A','SIM'),('other','FOREIGN','SIM'),('legacy','LEGACY','SIM')");
        jdbc.execute("INSERT INTO emcs_collect_event VALUES('e1','own',1,1,1,1,0,'sha','{}'),('e2','other',2,2,2,1,0,'sha','{}'),('e3','legacy',3,3,3,1,0,'sha','{}')");
        var service=new CollectionService(jdbc,mapper,true,false,"unused-test-only");
        try(var scope=EmcsCentralScope.bind(context(false,"EMCS:2008",Set.of("VIEW")))){
            assertEquals(1L,service.list(null,null,1,20).get("total"));
            assertEquals("e1",service.detail(null,"e1").get("event_id"));
            assertEquals(404,assertThrows(BusinessException.class,()->service.detail(null,"e2")).status());
            assertEquals(404,assertThrows(BusinessException.class,()->service.detail(null,"e3")).status());
        }
    }
    @Test void inspectionSnapshotCapturesTrustedTupleAndCannotReadForeignOrLegacy() throws Exception {
        jdbc.execute("CREATE TABLE e_suggestion(area_id bigint,status varchar)");
        jdbc.execute("INSERT INTO e_suggestion VALUES(1,'pending'),(3,'pending'),(4,'pending')");
        jdbc.execute("CREATE TABLE ai_inspection_report(id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,report_date date,trigger_type varchar(16),status varchar(16),summary varchar,findings_json json,stats_json json,model_name varchar,elapsed_ms integer,created_at timestamp,project_id varchar,region_id varchar,subsystem_code varchar)");
        var service=new AgentService(jdbc,mapper,now,org.mockito.Mockito.mock(CloudLlmClient.class));
        try(var scope=EmcsCentralScope.bind(context(false,"EMCS:2015",Set.of("VIEW","ADD")))){
            assertThrows(IllegalArgumentException.class,()->service.run("SIM-JOINT-OVERLONG-TRIGGER"));
            long id=service.run("SIM-JOINT-TEST");
            assertEquals("summary_only",service.detail(id).get("status"));
            assertEquals(java.sql.Date.valueOf(now.toLocalDate()),jdbc.queryForObject("SELECT report_date FROM ai_inspection_report WHERE id=?",java.sql.Date.class,id));
            assertEquals("PROJECT_B",jdbc.queryForObject("SELECT project_id FROM ai_inspection_report WHERE id=?",String.class,id));
            assertEquals("SHEN_TIE",jdbc.queryForObject("SELECT region_id FROM ai_inspection_report WHERE id=?",String.class,id));
            assertTrue(((Map<?,?>)service.detail(id).get("stats")).values().stream().allMatch(value->((Number)value).intValue()==1));
            jdbc.execute("INSERT INTO ai_inspection_report(id,project_id,region_id,subsystem_code) VALUES(100,'PROJECT_B','HA_TIE','EMCS'),(101,NULL,NULL,NULL)");
            assertEquals(1,service.list(1,20).get("total"));
            assertEquals(404,assertThrows(BusinessException.class,()->service.detail(100)).status());
            assertEquals(404,assertThrows(BusinessException.class,()->service.detail(101)).status());
        }
    }
    @Test void costRecordScopeRejectsForeignReviewAndRetainsRegionalCreation() throws Exception {
        jdbc.execute("CREATE TABLE e_cost_record(stat_month varchar,energy_type_code varchar,cost_version integer,object_type varchar,object_id bigint)");
        jdbc.execute("INSERT INTO e_cost_record VALUES('2026-07','electricity',2,'area',1),('2026-07','electricity',9,'area',3)");
        jdbc.execute("CREATE TABLE e_cost_recompute_record(recompute_id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,period_key varchar,stat_month varchar,energy_type_code varchar,scope varchar,old_cost_version integer,new_cost_version integer,trigger_reason varchar,trigger_type varchar,triggered_by varchar,triggered_at timestamp,tariff_snapshot_json varchar,alloc_rule_snapshot_json varchar,diff_summary_json varchar,review_status varchar,reviewed_by varchar,reviewed_at timestamp,review_remark varchar,project_id varchar,region_id varchar,subsystem_code varchar)");
        var service=new CostService(jdbc,mapper,now);
        try(var scope=EmcsCentralScope.bind(context(false,"EMCS:2013",Set.of("VIEW","ADD","CONFIRM")))){
            var row=service.recompute(Map.of("statMonth","2026-07","energyType","electricity","triggerReason","SIM"),"operator");
            long id=((Number)row.get("recomputeId")).longValue();
            assertEquals("v2",row.get("oldCostVersion"));
            assertEquals("SHEN_TIE",row.get("region_id"));
            assertEquals("approved",service.review(id,Map.of("action","approved"),"reviewer").get("reviewStatus"));
            jdbc.execute("INSERT INTO e_cost_recompute_record(recompute_id,review_status,project_id,region_id,subsystem_code) VALUES(100,'pending','PROJECT_B','HA_TIE','EMCS'),(101,'pending',NULL,NULL,NULL)");
            assertEquals(1,service.recomputations(null,null,null,1,20).get("total"));
            assertEquals(404,assertThrows(BusinessException.class,()->service.recomputation(100)).status());
            assertEquals(409,assertThrows(BusinessException.class,()->service.review(100,Map.of("action","approved"),"reviewer")).status());
            assertEquals("pending",jdbc.queryForObject("SELECT review_status FROM e_cost_recompute_record WHERE recompute_id=100",String.class));
        }
    }
    @Test void unavailableVerificationDoesNotCreateFakeCompletedZeroSnapshot() throws Exception {
        jdbc.execute("CREATE TABLE e_suggestion(suggestion_id bigint,area_id bigint,row_version integer)");
        jdbc.execute("INSERT INTO e_suggestion VALUES(1,1,1),(3,3,1)");
        var service=new SuggestionService(jdbc,mapper,now);
        try(var scope=EmcsCentralScope.bind(context(false,"EMCS:2011",Set.of("CONFIRM")))){
            assertEquals(409,assertThrows(BusinessException.class,()->service.verify(1,Map.of("rowVersion",1),"operator")).status());
            assertEquals(404,assertThrows(BusinessException.class,()->service.verify(3,Map.of("rowVersion",1),"operator")).status());
            assertEquals(1,jdbc.queryForObject("SELECT row_version FROM e_suggestion WHERE suggestion_id=1",Integer.class));
        }
    }

}
