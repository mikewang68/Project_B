package com.bdemo.energy.cost;

import com.bdemo.common.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CostService {
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final LocalDateTime demoNow;
    public CostService(JdbcTemplate jdbc, ObjectMapper json, @Value("${b-demo.demo-now}") LocalDateTime demoNow) { this.jdbc=jdbc; this.json=json; this.demoNow=demoNow; }

    public Map<String,Object> monthView(String month, String zone, String energy, String groupBy, String focus) {
        String statMonth = blank(month) ? demoNow.toString().substring(0,7) : month;
        String type = switch (groupBy) { case "equipment" -> "equipment"; case "energyType" -> "system"; default -> "area"; };
        String areaSql = "ALL".equalsIgnoreCase(zone) ? "" : type.equals("area") ? " AND a.area_code=?" : " AND eq_area.area_code=?";
        List<Object> params = new ArrayList<>(List.of(statMonth, energy, type)); if (!areaSql.isEmpty()) params.add("AREA-"+zone.toUpperCase());
        List<Map<String,Object>> rows = jdbc.queryForList("""
                SELECT c.*,a.area_code,a.area_name,eq.equipment_code,eq.equipment_name,
                  eq_area.area_code eq_area_code,eq_area.area_name eq_area_name
                FROM e_cost_record c LEFT JOIN e_area a ON c.object_type='area' AND a.area_id=c.object_id
                LEFT JOIN e_equipment eq ON c.object_type='equipment' AND eq.equipment_id=c.object_id
                LEFT JOIN e_area eq_area ON eq_area.area_id=eq.area_id
                WHERE c.stat_month=? AND c.energy_type_code=? AND c.object_type=? AND c.is_current=1 %s
                ORDER BY c.total_cost DESC
                """.formatted(areaSql), params.toArray());
        Map<String,Object> summaryRow = first("""
                SELECT SUM(usage_qty) usage_qty,SUM(total_cost) total_cost,MAX(cost_version) cost_version,
                  MAX(status) status FROM e_cost_record WHERE stat_month=? AND energy_type_code=?
                  AND object_type='system' AND is_current=1
                """, statMonth, energy);
        double totalCost = num(summaryRow.get("total_cost")), usage = num(summaryRow.get("usage_qty"));
        String previous = LocalDate.parse(statMonth+"-01").minusMonths(1).toString().substring(0,7);
        double previousCost = num(first("SELECT SUM(total_cost) total_cost FROM e_cost_record WHERE stat_month=? AND energy_type_code=? AND object_type='system' AND is_current=1", previous, energy).get("total_cost"));
        List<Map<String,Object>> groups = rows.stream().map(this::costItem).toList();
        List<Map<String,Object>> trend = jdbc.queryForList("""
                SELECT stat_month,SUM(total_cost) total_cost FROM e_cost_record WHERE energy_type_code=?
                  AND object_type='system' AND is_current=1 GROUP BY stat_month ORDER BY stat_month
                """, energy).stream().map(row -> map("statMonth",row.get("stat_month"),"totalCost",num(row.get("total_cost")),
                "momPct",null,"periodState","partial","dataStart",row.get("stat_month")+"-01","dataEnd",row.get("stat_month")+"-31",
                "periodNote",null,"anomaly",map("detected",false,"eventId",null,"ruleCode",null,"level",null,"note",null),"drillParams",null)).toList();
        List<Map<String,Object>> top = jdbc.queryForList("""
                SELECT c.*,eq.equipment_code,eq.equipment_name,a.area_code,a.area_name FROM e_cost_record c
                JOIN e_equipment eq ON eq.equipment_id=c.object_id JOIN e_area a ON a.area_id=eq.area_id
                WHERE c.stat_month=? AND c.energy_type_code=? AND c.object_type='equipment' AND c.is_current=1
                  %s ORDER BY c.total_cost DESC LIMIT 5
                """.formatted("ALL".equalsIgnoreCase(zone)?"":" AND a.area_code=?"),
                ("ALL".equalsIgnoreCase(zone)?new Object[]{statMonth,energy}:new Object[]{statMonth,energy,"AREA-"+zone.toUpperCase()}));
        List<Map<String,Object>> tou = List.of(tou("peak",summaryRow,usage,totalCost),tou("flat",summaryRow,usage,totalCost),tou("valley",summaryRow,usage,totalCost));
        List<Map<String,Object>> peak = jdbc.queryForList("""
                SELECT h.stat_time,h.object_id,h.avg_power_kw,h.total_value,eq.equipment_code,eq.equipment_name
                FROM e_stat_hour h JOIN e_equipment eq ON eq.equipment_id=h.object_id
                WHERE h.object_type='equipment' AND h.energy_type_code=? AND DATE_FORMAT(h.stat_time,'%%Y-%%m')=?
                ORDER BY h.avg_power_kw DESC LIMIT 5
                """.formatted(), energy, statMonth).stream().map(row -> map("start",dateTime(row.get("stat_time")).toString(),
                "end",dateTime(row.get("stat_time")).plusHours(1).toString(),"object",map("type","equipment","id",row.get("object_id"),
                        "code",row.get("equipment_code"),"name",row.get("equipment_name")),"loadKw",row.get("avg_power_kw"),
                "usageQty",row.get("total_value"),"cost",0,"tariffVersion","v"+summaryRow.getOrDefault("cost_version",1),"sourcePointIds",List.of())).toList();
        return map("filters",map("statMonth",statMonth,"zone",zone,"energyType",energy,"groupBy",groupBy,"focus",focus),
                "period",map("statMonth",statMonth,"state","inProgress","periodStart",statMonth+"-01","periodEnd",demoNow.toLocalDate().toString(),
                        "dataStart",statMonth+"-01","dataEnd",demoNow.toLocalDate().toString(),"asOf",demoNow.toLocalDate().toString(),"label",statMonth+"（进行中）"),
                "signature","COST-VIEW-SHA256:"+Integer.toHexString((statMonth+zone+energy+groupBy).hashCode()),
                "summary",map("usageQty",usage,"totalCost",totalCost,"previousMonthCost",previousCost,
                        "momPct",previousCost==0?null:round((totalCost-previousCost)*100/previousCost),"status",summaryRow.get("status"),
                        "currentCostVersion","v"+summaryRow.getOrDefault("cost_version",1),"quality",map("coverageRatio","1.0000")),
                "groups",groups,"monthTrend",trend,"touComposition",tou,"topCostObjects",top.stream().map(this::costItem).toList(),
                "peakWindows",peak,"costWarnings",List.of(),"anomalyEvidence",List.of());
    }

    public Map<String,Object> trace(String month,String objectType,Long objectId,String energy,String version) {
        if (!"system".equals(objectType) && objectId==null) throw new IllegalArgumentException("area/equipment 反查必须提供 objectId");
        int v=blank(version)?-1:Integer.parseInt(version.replace("v",""));
        String sql="SELECT * FROM e_cost_record WHERE stat_month=? AND object_type=? AND object_id=? AND energy_type_code=? "+(v<0?"AND is_current=1":"AND cost_version=?")+" LIMIT 1";
        Object[] params=v<0?new Object[]{month,objectType,objectId==null?0:objectId,energy}:new Object[]{month,objectType,objectId==null?0:objectId,energy,v};
        List<Map<String,Object>> rows=jdbc.queryForList(sql,params); if(rows.isEmpty()) throw new BusinessException(404,"成本记录不存在");
        Map<String,Object> row=rows.get(0); return map("record",costItem(row),"tariffSnapshot",jsonObj(row.get("tariff_snapshot_json")),
                "allocationRuleSnapshot",jsonObj(row.get("alloc_rule_snapshot_json")),"sourceStatSnapshot",jsonObj(row.get("source_stat_snapshot_json")));
    }

    public List<Map<String,Object>> tariffs(String energy,String on,boolean history) { StringBuilder sql=new StringBuilder("SELECT * FROM e_tariff_version WHERE 1=1"); List<Object> p=new ArrayList<>(); add(sql,p," AND energy_type_code=?",energy); if(!blank(on)){sql.append(" AND effective_from<=? AND (effective_to IS NULL OR effective_to>=?)");p.add(on);p.add(on);} if(!history) sql.append(" AND effective_to IS NULL"); sql.append(" ORDER BY energy_type_code,tou_period,version_no DESC"); return jdbc.queryForList(sql.toString(),p.toArray()).stream().map(row->map("tariffId",row.get("tariff_id"),"energyType",row.get("energy_type_code"),"touPeriod",row.get("tou_period"),"price",row.get("price"),"currency",row.get("currency"),"effectiveFrom",row.get("effective_from"),"effectiveTo",row.get("effective_to"),"versionNo",row.get("version_no"),"remark",row.get("remark"),"createBy",row.get("create_by"),"createTime",row.get("create_time"))).toList(); }
    @Transactional public Map<String,Object> createTariff(Map<String,Object>b,String user){ req(b,"energyType","touPeriod","price","effectiveFrom"); Integer v=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM e_tariff_version WHERE energy_type_code=? AND tou_period=?",Integer.class,b.get("energyType"),b.get("touPeriod")); jdbc.update("INSERT INTO e_tariff_version(energy_type_code,tou_period,price,currency,effective_from,effective_to,version_no,remark,create_by,create_time) VALUES(?,?,?,?,?,?,?,?,?,?)",b.get("energyType"),b.get("touPeriod"),b.get("price"),b.getOrDefault("currency","CNY"),b.get("effectiveFrom"),b.get("effectiveTo"),v,b.get("remark"),user,Timestamp.valueOf(demoNow)); return map("versionNo",v,"created",true); }
    public List<Map<String,Object>> allocationRules(String scope,String on,boolean history){StringBuilder sql=new StringBuilder("SELECT * FROM e_cost_alloc_rule WHERE 1=1");List<Object>p=new ArrayList<>();add(sql,p," AND scope=?",scope);if(!blank(on)){sql.append(" AND effective_from<=? AND (effective_to IS NULL OR effective_to>=?)");p.add(on);p.add(on);}if(!history)sql.append(" AND effective_to IS NULL");sql.append(" ORDER BY version_no DESC");return jdbc.queryForList(sql.toString(),p.toArray()).stream().map(r->map("ruleId",r.get("rule_id"),"ruleName",r.get("rule_name"),"scope",r.get("scope"),"method",r.get("method"),"config",jsonObj(r.get("config_json")),"effectiveFrom",r.get("effective_from"),"effectiveTo",r.get("effective_to"),"versionNo",r.get("version_no"),"createBy",r.get("create_by"),"createTime",r.get("create_time"),"affectedMeters",List.of())).toList();}
    @Transactional public Map<String,Object> createAllocation(Map<String,Object>b,String user){req(b,"ruleName","method","effectiveFrom");Integer v=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM e_cost_alloc_rule",Integer.class);jdbc.update("INSERT INTO e_cost_alloc_rule(rule_name,scope,method,config_json,effective_from,effective_to,version_no,create_by,create_time) VALUES(?,?,?,?,?,?,?,?,?)",b.get("ruleName"),b.get("scope"),b.get("method"),toJson(b.getOrDefault("config",Map.of())),b.get("effectiveFrom"),b.get("effectiveTo"),v,user,Timestamp.valueOf(demoNow));return map("versionNo",v,"created",true);}
    public Map<String,Object> recomputations(String month,String energy,String status,int page,int size){if(page<1||size<1||size>100)throw new IllegalArgumentException("分页参数无效");StringBuilder where=new StringBuilder(" WHERE 1=1");List<Object>p=new ArrayList<>();add(where,p," AND stat_month=?",month);add(where,p," AND energy_type_code=?",energy);add(where,p," AND review_status=?",status);Integer total=jdbc.queryForObject("SELECT COUNT(*) FROM e_cost_recompute_record"+where,Integer.class,p.toArray());p.add(size);p.add((page-1)*size);List<Map<String,Object>>items=jdbc.queryForList("SELECT * FROM e_cost_recompute_record"+where+" ORDER BY recompute_id DESC LIMIT ? OFFSET ?",p.toArray()).stream().map(this::recomputeItem).toList();return map("items",items,"total",total,"filters",map("statMonth",month,"energyType",energy,"reviewStatus",status,"pageNum",page,"pageSize",size));}
    public Map<String,Object> recomputation(long id){List<Map<String,Object>>r=jdbc.queryForList("SELECT * FROM e_cost_recompute_record WHERE recompute_id=?",id);if(r.isEmpty())throw new BusinessException(404,"成本重算不存在");return recomputeItem(r.get(0));}
    @Transactional public Map<String,Object> recompute(Map<String,Object>b,String user){req(b,"statMonth","energyType","triggerReason");String month=String.valueOf(b.get("statMonth")),energy=String.valueOf(b.get("energyType"));Map<String,Object>cur=first("SELECT COALESCE(MAX(cost_version),1) v FROM e_cost_record WHERE stat_month=? AND energy_type_code=?",month,energy);int old=((Number)cur.get("v")).intValue(),next=old+1;KeyHolder kh=new GeneratedKeyHolder();jdbc.update(c->{PreparedStatement ps=c.prepareStatement("INSERT INTO e_cost_recompute_record(period_key,stat_month,energy_type_code,scope,old_cost_version,new_cost_version,trigger_reason,trigger_type,triggered_by,triggered_at,tariff_snapshot_json,alloc_rule_snapshot_json,diff_summary_json,review_status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'pending')",Statement.RETURN_GENERATED_KEYS);int i=1;ps.setString(i++,month+"M");ps.setString(i++,month);ps.setString(i++,energy);ps.setString(i++,String.valueOf(b.getOrDefault("scope","system")));ps.setInt(i++,old);ps.setInt(i++,next);ps.setString(i++,String.valueOf(b.get("triggerReason")));ps.setString(i++,"manual");ps.setString(i++,user);ps.setTimestamp(i++,Timestamp.valueOf(demoNow));ps.setString(i++,"{}");ps.setString(i++,"{}");ps.setString(i,"{}");return ps;},kh);return recomputation(kh.getKey().longValue());}
    @Transactional public Map<String,Object> review(long id,Map<String,Object>b,String user){String action=String.valueOf(b.get("action"));if(!List.of("approved","rejected","approve","reject").contains(action))throw new IllegalArgumentException("action 无效");String status=action.startsWith("approve")?"approved":"rejected";int n=jdbc.update("UPDATE e_cost_recompute_record SET review_status=?,reviewed_by=?,reviewed_at=?,review_remark=? WHERE recompute_id=? AND review_status='pending'",status,user,Timestamp.valueOf(demoNow),b.get("remark"),id);if(n==0)throw new BusinessException(409,"重算记录不存在或已复核");return recomputation(id);}

    private Map<String,Object> costItem(Map<String,Object>r){String type=String.valueOf(r.get("object_type"));Object code="area".equals(type)?r.get("area_code"):"equipment".equals(type)?r.get("equipment_code"):"SYSTEM";Object name="area".equals(type)?r.get("area_name"):"equipment".equals(type)?r.get("equipment_name"):"全站";return map("objectType",type,"objectId",r.get("object_id"),"objectCode",code,"objectName",name,"area",map("code",fallback(r.get("area_code"),r.get("eq_area_code")),"name",fallback(r.get("area_name"),r.get("eq_area_name"))),"energyType",r.get("energy_type_code"),"usageQty",r.get("usage_qty"),"totalCost",r.get("total_cost"),"momPct",null,"currentCostVersion","v"+r.getOrDefault("cost_version",1),"status",r.get("status"),"signature",r.get("signature"));}
    private Map<String,Object> tou(String prefix,Map<String,Object>r,double usage,double cost){double qty=num(r.get(prefix+"_qty")),amount=num(r.get(prefix+"_cost"));return map("period",prefix,"usageQty",qty,"usagePct",usage==0?0:round(qty*100/usage),"price",qty==0?0:round(amount/qty),"cost",amount,"costPct",cost==0?0:round(amount*100/cost),"tariffVersion","v"+r.getOrDefault("cost_version",1));}
    private Map<String,Object> recomputeItem(Map<String,Object>r){Map<String,Object>o=new LinkedHashMap<>(r);o.put("recomputeId",r.get("recompute_id"));o.put("periodKey",r.get("period_key"));o.put("statMonth",r.get("stat_month"));o.put("energyType",r.get("energy_type_code"));o.put("oldCostVersion","v"+r.get("old_cost_version"));o.put("newCostVersion","v"+r.get("new_cost_version"));o.put("reviewStatus",r.get("review_status"));o.put("diffSummary",jsonObj(r.get("diff_summary_json")));return o;}
    private Map<String,Object> first(String sql,Object...p){List<Map<String,Object>>r=jdbc.queryForList(sql,p);return r.isEmpty()?Map.of():r.get(0);}private void add(StringBuilder s,List<Object>p,String q,Object v){if(v!=null&&!v.toString().isBlank()){s.append(q);p.add(v);}}private void req(Map<String,Object>b,String...keys){for(String k:keys)if(b.get(k)==null||b.get(k).toString().isBlank())throw new IllegalArgumentException(k+" 不能为空");}
    private double num(Object v){return v instanceof Number n?n.doubleValue():0;}private double round(double v){return Math.round(v*100.0)/100.0;}private Object fallback(Object v,Object f){return v==null?f:v;}private boolean blank(String v){return v==null||v.isBlank();}private LocalDateTime dateTime(Object v){return v instanceof LocalDateTime l?l:((Timestamp)v).toLocalDateTime();}
    private Map<String,Object>jsonObj(Object v){if(v==null)return Map.of();try{return json.readValue(v.toString(),new TypeReference<>(){});}catch(Exception e){return Map.of();}}private String toJson(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalArgumentException("JSON 序列化失败");}}private Map<String,Object>map(Object...p){Map<String,Object>m=new LinkedHashMap<>();for(int i=0;i<p.length;i+=2)m.put((String)p[i],p[i+1]);return m;}
}
