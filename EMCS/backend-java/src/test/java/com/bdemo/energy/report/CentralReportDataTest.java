package com.bdemo.energy.report;

import com.bdemo.config.EmcsCentralScope;
import com.bproject.iam.context.*;
import java.time.*;import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class CentralReportDataTest {
    UserContext context(){
        var t=new ScopeTriple("PROJECT_B","SHEN_TIE","EMCS");var f=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(t));
        return new UserContext(2,"subject","reader","subject","reader","L4_OPERATOR",true,"EMCS","PROJECT_B","SHEN_TIE","EMCS",ScopeSql.Mode.SCOPED,List.of(t),List.of(t),Set.of("role"),Set.of("EMCS:2014"),Map.of("EMCS:2014",Set.of("VIEW","EXPORT")),Set.of(),"1",f.my_info(),f.my_project_zone_info(),f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
    }
    @Test void regionalReportNeverUsesSystemTotalsOrOtherRegions() throws Exception {
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE e_area(area_id bigint,area_code varchar,project_id varchar,region_id varchar,subsystem_code varchar)");
        db.execute("INSERT INTO e_area VALUES(1,'AREA-A','PROJECT_B','SHEN_TIE','EMCS'),(2,'AREA-B','PROJECT_B','HA_TIE','EMCS'),(3,'AREA-C','PROJECT_A','SHEN_TIE','EMCS')");
        db.execute("CREATE TABLE e_stat_day(stat_date date,object_type varchar,object_id bigint,energy_type_code varchar,total_value decimal,coverage_ratio decimal)");
        db.execute("INSERT INTO e_stat_day VALUES('2026-11-01','area',1,'electricity',7,1),('2026-11-01','area',2,'electricity',91,1),('2026-11-01','area',3,'electricity',92,1),('2026-11-01','system',0,'electricity',99999,1)");
        db.execute("CREATE TABLE e_cost_record(stat_month varchar,object_type varchar,object_id bigint,energy_type_code varchar,is_current int,total_cost decimal,cost_version int,status varchar)");
        db.execute("CREATE TABLE e_alert_event(first_occur_time timestamp,status varchar,area_id bigint,object_type varchar,object_id bigint)");
        db.execute("CREATE TABLE e_suggestion(create_time timestamp,status varchar,area_id bigint,object_type varchar,object_id bigint)");
        try(var scope=EmcsCentralScope.bind(context())){
            var result=CentralReportData.assemble(db,LocalDateTime.of(2026,11,1,12,0),Map.of("templateCode","ENERGY_DAILY","period","2026-11-01"),Map.of("ENERGY_DAILY",List.of("usageSection")));
            var usage=(Map<?,?>)((Map<?,?>)result.get("sections")).get("usageSection");
            assertEquals(7,((Number)usage.get("total")).intValue());
        }
    }
}
