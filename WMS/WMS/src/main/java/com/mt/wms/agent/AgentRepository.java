package com.mt.wms.agent;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.mt.wms.agent.AgentModels.*;

@Repository
class AgentRepository {
    private final JdbcClient jdbc;
    AgentRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    JdbcClient.StatementSpec scoped(String sql,Scope s) {
        return jdbc.sql(sql).param("c",s.companyId()).param("w",s.warehouseId()).param("o",s.ownerId());
    }
    List<Map<String,Object>> search(Scope s,Map<String,Object> args,boolean receipts) {
        List<String> words=AgentMath.keywords(args.get("keywords"));
        String fromText=Objects.toString(args.get("fromDate"),"");
        String toText=Objects.toString(args.get("toDate"),"");
        LocalDate from=fromText.isBlank()?null:LocalDate.parse(fromText);
        LocalDate to=toText.isBlank()?from:LocalDate.parse(toText);
        int window=args.get("windowDays") instanceof Number n?Math.min(7,Math.max(0,n.intValue())):0;
        if (from!=null && (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from,to)>366))
            throw new IllegalArgumentException("查询日期范围应在一年内，且结束日期不能早于开始日期");
        String text="LOWER(CONCAT(g.code,' ',g.name,' ',COALESCE(g.specification,''),' ',ca.name,' ',COALESCE(g.remark,''),' ',r.batch_code,' ',r.supplier_code))";
        String sql;
        if (receipts) {
            sql="""
                SELECT r.id AS receipt_id,r.order_id,so.order_code,g.id AS good_id,g.code AS good_code,g.name AS good_name,
                       COALESCE(g.specification,'-') AS specification,COALESCE(g.unit,'-') AS unit,
                       r.batch_code,r.supplier_code,r.quantity AS received_qty,
                       to_char(r.created_at AT TIME ZONE 'Asia/Shanghai','YYYY-MM-DD HH24:MI') AS received_at,
                       l.code AS received_location,COALESCE(p.name,'-') AS partner_name,
                       COALESCE((SELECT SUM(b.available_qty) FROM inv_balance b WHERE b.company_id=:c AND b.warehouse_id=:w AND b.owner_id=:o AND b.good_id=r.good_id AND b.batch_code=r.batch_code AND b.supplier_code=r.supplier_code),0) AS available_qty,
                       COALESCE((SELECT string_agg(DISTINCT bl.code,', ') FROM inv_balance b JOIN wms_location bl ON bl.id=b.location_id WHERE b.company_id=:c AND b.warehouse_id=:w AND b.owner_id=:o AND b.good_id=r.good_id AND b.batch_code=r.batch_code AND b.supplier_code=r.supplier_code AND b.available_qty+b.allocated_qty+b.frozen_qty>0),'-') AS current_locations
                FROM stockin_receipt r JOIN stockin_order so ON so.id=r.order_id JOIN wms_good g ON g.id=r.good_id
                JOIN wms_category ca ON ca.id=g.category_id JOIN wms_location l ON l.id=r.location_id
                LEFT JOIN wms_partner p ON p.id=so.partner_id
                WHERE r.company_id=:c AND r.warehouse_id=:w AND r.owner_id=:o
                """;
            text=text.replace("))",",' ',COALESCE(p.name,'')))");
        } else {
            sql="""
                SELECT r.id AS balance_id,g.id AS good_id,g.code AS good_code,g.name AS good_name,
                       COALESCE(g.specification,'-') AS specification,COALESCE(g.unit,'-') AS unit,
                       r.batch_code,r.supplier_code,l.code AS current_locations,
                       r.available_qty,r.allocated_qty,r.frozen_qty,r.available_qty+r.allocated_qty+r.frozen_qty AS total_qty
                FROM inv_balance r JOIN wms_good g ON g.id=r.good_id JOIN wms_category ca ON ca.id=g.category_id
                JOIN wms_location l ON l.id=r.location_id
                WHERE r.company_id=:c AND r.warehouse_id=:w AND r.owner_id=:o
                """;
        }
        if (!words.isEmpty()) {
            List<String> predicates=new ArrayList<>();
            for(int i=0;i<words.size();i++) predicates.add(text+" LIKE :k"+i);
            sql+=" AND ("+String.join(" OR ",predicates)+")";
        }
        if (receipts && from!=null) sql+=" AND r.created_at>=:start AND r.created_at<:end";
        sql+=" ORDER BY r.id DESC LIMIT 201";
        var query=scoped(sql,s);
        for(int i=0;i<words.size();i++) query=query.param("k"+i,"%"+words.get(i).toLowerCase(Locale.ROOT)+"%");
        if (receipts && from!=null) query=query.param("start",from.minusDays(window).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime())
                .param("end",to.plusDays(window+1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime());
        List<Map<String,Object>> rows=query.query().listOfRows();
        List<Map<String,Object>> ranked=new ArrayList<>();
        for(var row:rows) {
            Map<String,Object> item=new LinkedHashMap<>(row);
            String content=row.values().toString().toLowerCase(Locale.ROOT);
            List<String> reasons=new ArrayList<>(); int score=0;
            for(String word:words) if(content.contains(word.toLowerCase(Locale.ROOT))) {score+=15;reasons.add("匹配线索："+word);}
            if(receipts && from!=null) {
                LocalDate day=LocalDate.parse(row.get("received_at").toString().substring(0,10));
                if(!day.isBefore(from)&&!day.isAfter(to)) {score+=50;reasons.add("实际收货日期符合"+from+"至"+to);}
                else {score+=15;reasons.add("在扩展日期范围内，非精确日期匹配");}
            }
            item.put("relevance",Math.min(100,score));item.put("match_reasons",reasons);ranked.add(item);
        }
        ranked.sort(Comparator.<Map<String,Object>>comparingInt(x->((Number)x.get("relevance")).intValue()).reversed());
        if(rows.size()>200 && !ranked.isEmpty()) ranked.get(0).put("search_notice","匹配超过200条，仅对最近200条排序，请补充线索缩小范围");
        return ranked.stream().limit(20).toList();
    }
    List<Map<String,Object>> balanceWeights(Scope s) {
        String sql="""
            SELECT b.location_id,b.good_id,b.available_qty+b.allocated_qty+b.frozen_qty AS quantity,
                   g.weight_kg,COALESCE(g.unit,'-') AS unit
            FROM inv_balance b JOIN wms_good g ON g.id=b.good_id
            WHERE b.company_id=:c AND b.warehouse_id=:w AND (:allOwners OR b.owner_id=:o)
              AND b.available_qty+b.allocated_qty+b.frozen_qty>0
            """;
        return scoped(sql,s).param("allOwners",s.allOwners()).query().listOfRows();
    }
    List<Map<String,Object>> locations(Scope s) {
        return scoped("SELECT l.id,l.code,a.name AS area_name,l.max_weight_kg,l.system_defined FROM wms_location l JOIN wms_area a ON a.id=l.area_id WHERE l.company_id=:c AND l.warehouse_id=:w AND l.status='ENABLED' ORDER BY l.code",s).query().listOfRows();
    }
    List<Map<String,Object>> warnings(Scope s) {
        return scoped("""
            SELECT g.code AS good_code,g.name AS good_name,g.unit,g.min_quantity AS threshold_qty,
                   COALESCE(SUM(b.available_qty),0) AS available_qty,
                   COALESCE(SUM(b.frozen_qty),0) AS frozen_qty
            FROM wms_good g LEFT JOIN inv_balance b ON b.good_id=g.id AND b.company_id=:c AND b.warehouse_id=:w AND b.owner_id=:o
            WHERE g.company_id=:c AND g.owner_id=:o AND g.status='ENABLED'
            GROUP BY g.id,g.code,g.name,g.unit,g.min_quantity
            HAVING COALESCE(SUM(b.available_qty),0)<g.min_quantity OR COALESCE(SUM(b.frozen_qty),0)>0
            ORDER BY g.code
            """,s).query().listOfRows();
    }
    List<Map<String,Object>> replenishment(Scope s) {
        return scoped("""
            SELECT r.id,g.code AS good_code,g.name AS good_name,l.code AS location_code,r.min_qty,r.max_qty,
                   COALESCE((SELECT SUM(b.available_qty) FROM inv_balance b WHERE b.company_id=:c AND b.warehouse_id=:w AND b.owner_id=:o AND b.good_id=r.good_id AND b.location_id=r.location_id),0) AS available_qty
            FROM inv_replenishment_rule r JOIN wms_good g ON g.id=r.good_id JOIN wms_location l ON l.id=r.location_id
            WHERE r.company_id=:c AND r.warehouse_id=:w AND r.owner_id=:o AND r.status='ENABLED'
            """,s).query().listOfRows();
    }
    long createTask(Scope s,String question) {
        return scoped("INSERT INTO wms_agent_task(company_id,warehouse_id,owner_id,user_id,question) VALUES(:c,:w,:o,:u,:q) RETURNING id",s).param("u",s.userId()).param("q",question).query(Long.class).single();
    }
    void finishTask(long id,String answer,String events,String state) {
        jdbc.sql("UPDATE wms_agent_task SET answer=:a,tool_events=:e,state=:s,finished_at=CURRENT_TIMESTAMP WHERE id=:id")
            .param("a",answer).param("e",events).param("s",state).param("id",id).update();
    }
    List<Map<String,Object>> tasks(Scope s) {
        return scoped("SELECT id,question,answer,state,created_at FROM wms_agent_task WHERE company_id=:c AND warehouse_id=:w AND owner_id=:o AND user_id=:u ORDER BY id DESC LIMIT 30",s).param("u",s.userId()).query().listOfRows();
    }
    List<Map<String,Object>> alerts(Scope s) {
        return scoped("SELECT id,title,body,kind,level,state,read_at,created_at,updated_at FROM wms_agent_alert WHERE company_id=:c AND warehouse_id=:w AND owner_id=:o AND user_id=:u ORDER BY updated_at DESC LIMIT 100",s).param("u",s.userId()).query().listOfRows();
    }
    void readAlert(Scope s,long id) {
        scoped("UPDATE wms_agent_alert SET read_at=CURRENT_TIMESTAMP WHERE company_id=:c AND warehouse_id=:w AND owner_id=:o AND user_id=:u AND id=:id",s).param("u",s.userId()).param("id",id).update();
    }
    List<Schedule> schedules(Scope s) {
        return scoped("SELECT * FROM wms_agent_schedule WHERE company_id=:c AND warehouse_id=:w AND owner_id=:o AND user_id=:u ORDER BY id DESC",s).param("u",s.userId()).query(Schedule.class).list();
    }
    void saveSchedule(Scope s,Long id,ScheduleRequest r,String emails,OffsetDateTime next) {
        String sql=id==null?"INSERT INTO wms_agent_schedule(company_id,warehouse_id,owner_id,user_id,name,enabled,frequency,interval_minutes,daily_time,emails,low_stock,replenishment,frozen_stock,capacity,capacity_percent,cooldown_hours,next_run_at) VALUES(:c,:w,:o,:u,:name,:enabled,:freq,:minutes,:time,:emails,:low,:rep,:frozen,:capacity,:pct,:cool,:next)"
            :"UPDATE wms_agent_schedule SET name=:name,enabled=:enabled,frequency=:freq,interval_minutes=:minutes,daily_time=:time,emails=:emails,low_stock=:low,replenishment=:rep,frozen_stock=:frozen,capacity=:capacity,capacity_percent=:pct,cooldown_hours=:cool,next_run_at=:next,last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND company_id=:c AND warehouse_id=:w AND owner_id=:o AND user_id=:u AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)";
        var query=scoped(sql,s).param("u",s.userId()).param("name",r.name()).param("enabled",r.enabled()).param("freq",r.frequency()).param("minutes",r.intervalMinutes()).param("time",r.dailyTime()).param("emails",emails)
            .param("low",r.lowStock()).param("rep",r.replenishment()).param("frozen",r.frozenStock()).param("capacity",r.capacity()).param("pct",r.capacityPercent()).param("cool",r.cooldownHours()).param("next",next);
        if(id!=null) query=query.param("id",id);
        if(query.update()!=1) throw new IllegalArgumentException("巡检任务正在执行、不存在或无权修改，请稍后重试");
    }
    List<Schedule> due() {return jdbc.sql("SELECT * FROM wms_agent_schedule WHERE enabled=TRUE AND next_run_at<=CURRENT_TIMESTAMP ORDER BY next_run_at LIMIT 10").query(Schedule.class).list();}
    boolean claim(Schedule s,boolean manual) {
        return jdbc.sql("UPDATE wms_agent_schedule SET lease_until=CURRENT_TIMESTAMP+INTERVAL '10 minutes' WHERE id=:id AND (:manual OR (enabled=TRUE AND next_run_at<=CURRENT_TIMESTAMP)) AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)").param("id",s.id()).param("manual",manual).update()==1;
    }
    void finishSchedule(Schedule s,OffsetDateTime next,String error) {
        jdbc.sql("UPDATE wms_agent_schedule SET next_run_at=:next,last_run_at=CURRENT_TIMESTAMP,last_error=:e,lease_until=NULL WHERE id=:id").param("next",next).param("e",error).param("id",s.id()).update();
    }
    boolean permitted(Schedule s) {
        return jdbc.sql("""
            SELECT COUNT(*) FROM auth_user u JOIN auth_company c ON c.id=u.company_id
            JOIN wms_warehouse w ON w.id=:w AND w.company_id=u.company_id AND w.status='ENABLED'
            JOIN wms_owner o ON o.id=:o AND o.company_id=u.company_id AND o.status='ENABLED'
            WHERE u.id=:u AND u.company_id=:c AND u.status='ENABLED' AND u.login_enabled=TRUE AND c.status='ENABLED'
            AND EXISTS(SELECT 1 FROM auth_user_role ur JOIN auth_role_permission rp ON rp.role_id=ur.role_id JOIN auth_permission p ON p.id=rp.permission_id WHERE ur.user_id=u.id AND p.code='agent:manage')
            AND EXISTS(SELECT 1 FROM auth_user_role ur JOIN auth_role_permission rp ON rp.role_id=ur.role_id JOIN auth_permission p ON p.id=rp.permission_id WHERE ur.user_id=u.id AND p.code='inventory:read')
            AND (EXISTS(SELECT 1 FROM auth_user_role ur JOIN auth_role_permission rp ON rp.role_id=ur.role_id JOIN auth_permission p ON p.id=rp.permission_id WHERE ur.user_id=u.id AND p.code='tenant:all')
              OR (EXISTS(SELECT 1 FROM auth_user_warehouse uw WHERE uw.user_id=u.id AND uw.warehouse_id=:w) AND EXISTS(SELECT 1 FROM auth_user_owner uo WHERE uo.user_id=u.id AND uo.owner_id=:o)))
            """).param("c",s.companyId()).param("w",s.warehouseId()).param("o",s.ownerId()).param("u",s.userId()).query(Long.class).single()>0;
    }
    boolean allOwners(long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM auth_user_role ur JOIN auth_role_permission rp ON rp.role_id=ur.role_id JOIN auth_permission p ON p.id=rp.permission_id WHERE ur.user_id=:u AND p.code='tenant:all'").param("u",userId).query(Long.class).single()>0;
    }
    void snapshot(Scope s,int occupied,int total,BigDecimal weight,int missing) {
        scoped("INSERT INTO wms_agent_snapshot(company_id,warehouse_id,owner_id,occupied_locations,total_locations,known_weight_kg,unknown_weight_rows) VALUES(:c,:w,:o,:occ,:total,:weight,:missing)",s)
            .param("occ",occupied).param("total",total).param("weight",weight).param("missing",missing).update();
    }
    List<Map<String,Object>> snapshots(Scope s) {
        return scoped("SELECT occupied_locations,total_locations,known_weight_kg,unknown_weight_rows,created_at FROM wms_agent_snapshot WHERE company_id=:c AND warehouse_id=:w AND owner_id=:o ORDER BY id DESC LIMIT 30",s).query().listOfRows();
    }
    List<Map<String,Object>> weightData(Scope s) {
        return scoped("SELECT id,code,name,unit,weight_kg FROM wms_good WHERE company_id=:c AND owner_id=:o AND status='ENABLED' ORDER BY code",s).query().listOfRows();
    }
    void weight(Scope s,long id,BigDecimal value) {
        if(scoped("UPDATE wms_good SET weight_kg=:weight,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND company_id=:c AND owner_id=:o",s).param("id",id).param("weight",value).update()!=1) throw new IllegalArgumentException("货品不存在");
    }
    // 以下仅由服务调用；所有定时任务在运行前重新校验创建人的权限。
    JdbcClient client() {return jdbc;}
}
