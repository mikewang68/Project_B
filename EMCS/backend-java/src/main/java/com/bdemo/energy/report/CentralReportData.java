package com.bdemo.energy.report;

import com.bdemo.common.BusinessException;
import com.bdemo.config.EmcsCentralScope;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;

/** Canonical regional assembler shared by preview/export/archive. Never uses system-wide totals. */
final class CentralReportData {
    private CentralReportData(){}
    static Map<String,Object> assemble(JdbcTemplate db,LocalDateTime now,Map<String,Object> input,Map<String,List<String>> catalog){
        String code=String.valueOf(input.get("templateCode"));
        if(!catalog.containsKey(code))throw new IllegalArgumentException("templateCode 无效");
        Map<?,?> requested=input.get("filters") instanceof Map<?,?> m?m:Map.of();
        String energy=Objects.toString(requested.get("energyType"),"electricity");
        if(!energy.matches("[A-Za-z0-9_-]{1,32}"))throw new IllegalArgumentException("energyType 无效");
        String zone=Objects.toString(requested.get("zone"),"ALL");
        zone=new com.bdemo.config.EnergyDataScopeService(db).zone(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication(),zone);
        String period=Objects.toString(input.get("period"),code.equals("ENERGY_DAILY")?now.toLocalDate().toString():now.toLocalDate().minusMonths(1).toString().substring(0,7));
        LocalDate start=period.length()==10?LocalDate.parse(period):YearMonth.parse(period).atDay(1);
        LocalDate end=period.length()==10?start:YearMonth.from(start).atEndOfMonth();
        String nativeZone="";
        if(!zone.equalsIgnoreCase("ALL")){
            if(!zone.matches("[A-Za-z0-9_-]{1,32}"))throw new IllegalArgumentException("zone 无效");
            nativeZone=" AND a.area_code='AREA-"+zone.toUpperCase(Locale.ROOT)+"'";
        }
        String areas="SELECT a.area_id FROM e_area a WHERE 1=1"+EmcsCentralScope.area("a.area_id")+nativeZone;
        String objects="object_id IN ("+areas+")",type="area";
        Object equipment=requested.get("equipmentId");
        if(equipment!=null&&!equipment.toString().isBlank()){
            long id;try{id=Long.parseLong(equipment.toString());}catch(Exception e){throw new IllegalArgumentException("equipmentId 无效");}
            var n=db.queryForObject("SELECT count(*) FROM e_equipment WHERE equipment_id=? AND area_id IN ("+areas+")",Long.class,id);
            if(n==null||n!=1)throw new BusinessException(403,"设备不在授权范围");
            type="equipment";objects="object_id="+id;
        }
        var usage=first(db,"SELECT SUM(total_value) totalValue,AVG(coverage_ratio) coverage FROM e_stat_day WHERE stat_date>=? AND stat_date<=? AND object_type=? AND energy_type_code=? AND "+objects,start,end,type,energy);
        var cost=first(db,"SELECT SUM(total_cost) totalCost,MAX(cost_version) costVersion,MAX(status) reviewState FROM e_cost_record WHERE stat_month=? AND object_type=? AND energy_type_code=? AND is_current=1 AND "+objects,start.toString().substring(0,7),type,energy);
        String events=" AND area_id IN ("+areas+")";
        if(type.equals("equipment"))events+=" AND object_type='equipment' AND "+objects;
        var alert=first(db,"SELECT COUNT(*) total,SUM(CASE WHEN status IN ('closed','false_closed') THEN 1 ELSE 0 END) closed FROM e_alert_event WHERE first_occur_time>=? AND first_occur_time<?"+events,start,end.plusDays(1));
        var suggestion=first(db,"SELECT COUNT(*) total,SUM(CASE WHEN status IN ('valid_closed','invalid_closed','closed') THEN 1 ELSE 0 END) closed FROM e_suggestion WHERE create_time>=? AND create_time<?"+events,start,end.plusDays(1));
        var sections=new LinkedHashMap<String,Object>();
        for(String section:catalog.get(code))sections.put(section,switch(section){
            case "usageSection"->map("items",List.of(usage),"total",usage.get("totalValue"));
            case "costSection"->map("items",List.of(cost),"totalCost",cost.get("totalCost"),"reviewState",cost.get("reviewState"));
            case "alertSection"->map("items",List.of(alert),"total",alert.get("total"));
            case "suggestionSection"->map("items",List.of(suggestion),"total",suggestion.get("total"));
            // Missing coverage remains unknown; it is never reported as normal.
            default->map("coverage",usage.get("coverage"),"band",usage.get("coverage")==null?"unknown":"measured");
        });
        var context=EmcsCentralScope.current();
        return map("templateCode",code,"templateVersion","v1","period",map("start",start.toString(),"end",end.toString(),"label",period),
            "filters",map("zone",zone,"energyType",energy,"equipmentId",equipment,"centralScope",context.activeScope()),"generatedAt",now.toString(),
            "qualitySummary",map("coverage",usage.get("coverage"),"band",usage.get("coverage")==null?"unknown":"measured"),
            "versionSnapshots",map("formulaVersion","f-1.3","costVersion",cost.get("costVersion"),"templateVersion","v1"),"sections",sections);
    }
    private static Map<String,Object> first(JdbcTemplate db,String sql,Object...params){var rows=db.queryForList(sql,params);return rows.isEmpty()?Map.of():rows.get(0);}
    private static Map<String,Object> map(Object... pairs){var m=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
}
