package com.bproject.ehm.platform.persistence.opengauss;

import com.bproject.ehm.platform.security.*;
import com.bproject.iam.context.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CentralAggregateIsolationTest {
    UserContext context(String project,String region,boolean readonly){
        var t=new ScopeTriple(project,region,"EHMS");var f=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(t));
        return new UserContext(2,"subject","operator","subject","operator","L4_OPERATOR",readonly,"EHMS",
            project,region,"EHMS",ScopeSql.Mode.SCOPED,List.of(t),List.of(t),Set.of("job"),Set.of("EHMS:fleet"),
            Map.of("EHMS:fleet",Set.of("VIEW","ADD")),Set.of(),"1",f.my_info(),f.my_project_zone_info(),f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
    }
    @Test void sameAggregateIdCannotLeakAcrossRegionOrProjectAndLegacyCannotReadNewRows() throws Exception {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        var store=new OpenGaussJsonStore(jdbc,new ObjectMapper(),"ehm","aggregate_store",true);store.initialize();
        try(var p=ProjectContext.bind("PROJECT_B");var c=EhmCentralScope.bind(context("PROJECT_B","SHEN_TIE",false))){store.save("asset","ONE",Map.of("name","SHEN"));}
        try(var p=ProjectContext.bind("PROJECT_B");var c=EhmCentralScope.bind(context("PROJECT_B","HA_TIE",false))){
            assertTrue(store.find("asset","ONE",Map.class).isEmpty());assertEquals(0,store.count("asset"));
            store.save("asset","ONE",Map.of("name","HA"));
        }
        try(var p=ProjectContext.bind("PROJECT_A");var c=EhmCentralScope.bind(context("PROJECT_A","SHEN_TIE",false))){assertTrue(store.findAll("asset",Map.class).isEmpty());}
        try(var p=ProjectContext.bind("PROJECT_B");var c=EhmCentralScope.bind(context("PROJECT_B","SHEN_TIE",true))){
            assertEquals("SHEN",store.find("asset","ONE",Map.class).orElseThrow().get("name"));assertEquals(1,store.count("asset"));
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.save("asset","ONE",Map.of()));
        }
        try(var p=ProjectContext.bind("PROJECT_B")){
            assertEquals(0,store.count("asset"));store.save("asset","LEGACY",Map.of("name","old"));assertEquals(1,store.count("asset"));
        }
        assertNull(EhmCentralScope.current());
    }
}
