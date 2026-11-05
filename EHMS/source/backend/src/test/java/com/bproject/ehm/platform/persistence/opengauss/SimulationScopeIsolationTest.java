package com.bproject.ehm.platform.persistence.opengauss;

import com.bproject.ehm.platform.security.EhmCentralScope;
import com.bproject.ehm.platform.security.ProjectContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SimulationScopeIsolationTest {
    private JdbcTemplate db;
    private OpenGaussJsonStore store;
    private TransactionTemplate tx;
    private final CentralAggregateIsolationTest contexts=new CentralAggregateIsolationTest();
    private static final String PROJECT="SIM_SCOPE_TEST";

    @BeforeEach void prepare(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(source);
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        store=new OpenGaussJsonStore(db,new ObjectMapper(),"ehm","aggregate_store",true);
        store.initialize();
    }

    private void row(String project,String region,String system,String type,String id,String label){
        db.update("INSERT INTO ehm.aggregate_store"
                +" (project_id,region_id,subsystem_code,aggregate_type,aggregate_id,payload,updated_at)"
                +" VALUES (?,?,?,?,?,?,?)",project,region,system,type,id,
                "{\"label\":\""+label+"\"}",Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
    }

    @Test void prefixHistoryUsesAllThreeDimensionsAndEscapesWildcards() throws Exception {
        String id="SAMPLE-LIVE-P_1%!:2020";
        row(PROJECT,"SHEN_TIE","EHMS","sample-telemetry",id,"own");
        row(PROJECT,"HA_TIE","EHMS","sample-telemetry",id,"other-region");
        row("SIM_OTHER","SHEN_TIE","EHMS","sample-telemetry",id,"other-project");
        row(PROJECT,"SHEN_TIE","WMS","sample-telemetry",id,"other-system");
        row(PROJECT,"SHEN_TIE","EHMS","sample-telemetry","SAMPLE-LIVE-PX1AB!:2020","wildcard-decoy");
        row(PROJECT,"UNASSIGNED","EHMS","sample-telemetry",id,"legacy");
        try(var p=ProjectContext.bind(PROJECT);var c=EhmCentralScope.bind(contexts.context(PROJECT,"SHEN_TIE",true))){
            var values=store.findByIdPrefix("sample-telemetry","SAMPLE-LIVE-P_1%!:",Map.class);
            assertEquals(1,values.size());assertEquals("own",values.get(0).get("label"));
        }
        try(var p=ProjectContext.bind(PROJECT)){
            var values=store.findByIdPrefix("sample-telemetry","SAMPLE-LIVE-P_1%!:",Map.class);
            assertEquals(1,values.size());assertEquals("legacy",values.get(0).get("label"));
        }
    }

    @Test void controllerLockTargetsOneWritableTupleAndRejectsReadOnly() throws Exception {
        row(PROJECT,"SHEN_TIE","EHMS","simulation-control","control","own");
        row(PROJECT,"HA_TIE","EHMS","simulation-control","control","other-region");
        row(PROJECT,"SHEN_TIE","WMS","simulation-control","control","other-system");
        row(PROJECT,"UNASSIGNED","EHMS","simulation-control","control","legacy");
        try(var p=ProjectContext.bind(PROJECT);var c=EhmCentralScope.bind(contexts.context(PROJECT,"SHEN_TIE",false))){
            tx.executeWithoutResult(s->assertDoesNotThrow(store::lockSimulationControl));
        }
        try(var p=ProjectContext.bind(PROJECT);var c=EhmCentralScope.bind(contexts.context(PROJECT,"SHEN_TIE",true))){
            assertThrows(ResponseStatusException.class,store::lockSimulationControl);
        }
        try(var p=ProjectContext.bind(PROJECT)){
            tx.executeWithoutResult(s->assertDoesNotThrow(store::lockSimulationControl));
        }
    }

    @Test void retentionDeletesOnlyExpiredSyntheticSamplesWithinTheWritableTuple() throws Exception {
        String id="SAMPLE-LIVE-P1:2020";
        row(PROJECT,"SHEN_TIE","EHMS","sample-telemetry",id,"own");
        row(PROJECT,"HA_TIE","EHMS","sample-telemetry",id,"other-region");
        row("SIM_OTHER","SHEN_TIE","EHMS","sample-telemetry",id,"other-project");
        row(PROJECT,"SHEN_TIE","WMS","sample-telemetry",id,"other-system");
        row(PROJECT,"UNASSIGNED","EHMS","sample-telemetry",id,"legacy");
        row(PROJECT,"SHEN_TIE","EHMS","asset",id,"business");
        row(PROJECT,"SHEN_TIE","EHMS","sample-telemetry","REAL-P1:2020","real");
        row(PROJECT,"SHEN_TIE","EHMS","sample-telemetry","SAMPLE-LIVE-NEW","fresh");
        db.update("UPDATE ehm.aggregate_store SET updated_at=? WHERE aggregate_id='SAMPLE-LIVE-NEW'",
                Timestamp.from(Instant.parse("2030-01-01T00:00:00Z")));
        Instant before=Instant.parse("2025-01-01T00:00:00Z");
        try(var p=ProjectContext.bind(PROJECT);var c=EhmCentralScope.bind(contexts.context(PROJECT,"SHEN_TIE",true))){
            assertThrows(ResponseStatusException.class,()->store.pruneSimulationSamples(before));
        }
        assertEquals(8,db.queryForObject("SELECT COUNT(*) FROM ehm.aggregate_store",Integer.class));
        try(var p=ProjectContext.bind(PROJECT);var c=EhmCentralScope.bind(contexts.context(PROJECT,"SHEN_TIE",false))){
            assertEquals(1,store.pruneSimulationSamples(before));
            assertTrue(store.find("sample-telemetry",id,Map.class).isEmpty());
            assertTrue(store.find("asset",id,Map.class).isPresent());
        }
        assertEquals(7,db.queryForObject("SELECT COUNT(*) FROM ehm.aggregate_store",Integer.class));
        try(var p=ProjectContext.bind("PROJECT_B")){
            assertThrows(IllegalStateException.class,()->store.pruneSimulationSamples(before));
        }
    }
}
