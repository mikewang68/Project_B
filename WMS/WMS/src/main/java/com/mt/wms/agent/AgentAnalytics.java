package com.mt.wms.agent;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;
import static com.mt.wms.agent.AgentModels.*;

@Service
class AgentAnalytics {
    private final AgentRepository repository;
    AgentAnalytics(AgentRepository repository) {this.repository=repository;}
    static BigDecimal number(Object x) {return x==null?BigDecimal.ZERO:new BigDecimal(x.toString());}
    Map<String,Object> utilization(Scope scope,boolean snapshot) {
        Map<Long,BigDecimal> weights=new HashMap<>();Map<Long,Integer> missing=new HashMap<>();Set<Long> occupied=new HashSet<>();
        for(var balance:repository.balanceWeights(scope)) {
            long id=((Number)balance.get("location_id")).longValue();occupied.add(id);
            BigDecimal weight=AgentMath.itemWeight(number(balance.get("quantity")),number(balance.get("weight_kg")),Objects.toString(balance.get("unit"),"-"));
            if(weight==null) missing.merge(id,1,Integer::sum);else weights.merge(id,weight,BigDecimal::add);
        }
        List<Map<String,Object>> rows=new ArrayList<>();int total=0,used=0,unknown=0;
        BigDecimal sum=BigDecimal.ZERO;
        for(var location:repository.locations(scope)) {
            long id=((Number)location.get("id")).longValue();boolean stage=Boolean.TRUE.equals(location.get("system_defined"));
            BigDecimal known=weights.getOrDefault(id,BigDecimal.ZERO);int unknownRows=missing.getOrDefault(id,0);
            BigDecimal capacity=number(location.get("max_weight_kg"));
            BigDecimal pct=AgentMath.percent(known,capacity,unknownRows==0);
            Map<String,Object> row=new LinkedHashMap<>(location);row.put("occupied",occupied.contains(id));
            row.put("known_weight_kg",known);row.put("missing_weight_rows",unknownRows);row.put("utilization_percent",pct);
            row.put("status",stage?"STAGING":unknownRows>0?"MISSING_WEIGHT":capacity.signum()<=0?"MISSING_CAPACITY":pct.compareTo(BigDecimal.valueOf(100))>0?"OVERLOADED":pct.compareTo(BigDecimal.valueOf(85))>=0?"HIGH":occupied.contains(id)?"NORMAL":"EMPTY");
            rows.add(row);
            if(!stage) {total++;if(occupied.contains(id))used++;sum=sum.add(known);unknown+=unknownRows;}
        }
        if(snapshot) repository.snapshot(scope,used,total,sum,unknown);
        Map<String,Object> result=new LinkedHashMap<>();result.put("total_locations",total);result.put("occupied_locations",used);
        result.put("occupancy_percent",AgentMath.percent(BigDecimal.valueOf(used),BigDecimal.valueOf(total),true));
        result.put("known_weight_kg",sum);result.put("missing_weight_rows",unknown);result.put("full_warehouse",scope.allOwners());
        result.put("note",scope.allOwners()?"承重按当前仓库全部货主库存统计；系统暂存库位不计入正常库位占用率。捆按每捆重量换算，未知重量时不报告完整承重利用率。":"仅统计当前货主库存，承重比值是该货主占用共享容量的比例，不能代表整个库位真实利用率。空间体积利用率尚无容量数据。");
        result.put("locations",rows);result.put("history",repository.snapshots(scope));return result;
    }
    List<Finding> findings(Schedule job,Scope scope,Map<String,Object> utilization) {
        List<Finding> out=new ArrayList<>();
        for(var row:repository.warnings(scope)) {
            BigDecimal available=number(row.get("available_qty")),threshold=number(row.get("threshold_qty")),frozen=number(row.get("frozen_qty"));
            String code=row.get("good_code").toString(),name=row.get("good_name").toString();
            if(job.lowStock()&&available.compareTo(threshold)<0) out.add(new Finding("LOW:"+code,"LOW_STOCK",name+"低库存",
                code+"：可用量 "+available+"，安全库存 "+threshold+"，单位 "+Objects.toString(row.get("unit"),"未录入")+"。请核查待出库需求和补货来源。","WARNING"));
            if(job.frozenStock()&&frozen.signum()>0) out.add(new Finding("FROZEN:"+code,"FROZEN_STOCK",name+"存在冻结库存",
                code+"：冻结量 "+frozen+"，可用量 "+available+"。冻结原因需查看库存流水，不会自动解冻。","INFO"));
        }
        if(job.replenishment()) for(var row:repository.replenishment(scope)) {
            BigDecimal available=number(row.get("available_qty")),min=number(row.get("min_qty"));
            if(available.compareTo(min)<0) out.add(new Finding("REP:"+row.get("id"),"REPLENISHMENT",row.get("good_name")+"库位待补货",
                "库位 "+row.get("location_code")+"，可用 "+available+"，告警阈值 "+min+"，目标 "+row.get("max_qty")+"。建议检查来源库存后在库存中心执行移库。","WARNING"));
        }
        if(job.capacity()) {
            @SuppressWarnings("unchecked") List<Map<String,Object>> locations=(List<Map<String,Object>>)utilization.get("locations");
            for(var row:locations) {
                if(Boolean.TRUE.equals(row.get("system_defined"))) continue;
                if(((Number)row.get("missing_weight_rows")).intValue()>0) out.add(new Finding("WEIGHT:"+row.get("id"),"DATA_QUALITY","库位 "+row.get("code")+"重量数据缺失","请维护货品每单位重量；当前仅能确定已知重量，不能可靠计算承重利用率。","INFO"));
                else if(row.get("utilization_percent") instanceof BigDecimal pct && pct.compareTo(job.capacityPercent())>=0)
                    out.add(new Finding("CAP:"+row.get("id"),"CAPACITY","库位 "+row.get("code")+"承重占用偏高",
                        "已知存货重量 "+row.get("known_weight_kg")+" kg，承重上限 "+row.get("max_weight_kg")+" kg，占比 "+pct+"%。"+(scope.allOwners()?"":"该比例仅包括当前货主，不能代表全部库存。"),pct.compareTo(BigDecimal.valueOf(100))>0?"CRITICAL":"WARNING"));
            }
        }
        return out;
    }
}
