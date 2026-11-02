package com.bproject.ehm.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import java.util.*;

/** The scope IDs are source references, not confirmed physical serial numbers. */
public record SimulationCatalog(String version,String sourceFile,String sourceSha256,int sourceRowCount,
        int assetTypeCount,List<String> assumptions,List<Entry> entries,List<Coverage> coverage) {
    public record Entry(String scopeCode,String name,String profile,List<Integer> sourceRows,
            List<String> sourceModules,int simulationQuantity,Integer actualQuantity,
            boolean quantityConfirmed,String controlBoundary) {
        public String assetCode(){return "SAMPLE-LIVE-"+scopeCode;}
    }
    public record Coverage(String sheet,int row,String scopeCode,String name,List<String> coveredBy){}
    public record Metric(String code,String name,String unit,double upper){}
    public static SimulationCatalog load(ObjectMapper mapper){
        try(var in=new ClassPathResource("simulation/equipment-scope.json").getInputStream()){
            SimulationCatalog c=mapper.readValue(in,SimulationCatalog.class);
            var codes=new HashSet<String>();
            for(Entry e:c.entries())if(!codes.add(e.scopeCode()))throw new IllegalStateException("Duplicate scope");
            if(codes.size()!=c.assetTypeCount()||c.coverage().size()!=c.sourceRowCount()
                    ||c.coverage().stream().anyMatch(r->!codes.containsAll(r.coveredBy())))throw new IllegalStateException("Incomplete scope mapping");
            return c;
        }catch(Exception e){throw new IllegalStateException("Cannot load verified simulation scope",e);}
    }
    public static List<Metric> metrics(String profile){
        return switch(profile){
            case "WEIGHING" -> List.of(new Metric("ZERO","零点偏移绝对值","kg",50),new Metric("LATENCY","通信时延","ms",1000),new Metric("TEMP","仪表温度","℃",80));
            case "PROCESS" -> List.of(new Metric("TEMP","监测部位温度","℃",100),new Metric("PRESSURE","输送或除尘压差","Pa",2000),new Metric("CURRENT","辅机电流","A",100));
            case "ELECTRONIC" -> List.of(new Metric("TEMP","机内温度","℃",85),new Metric("LATENCY","通信时延","ms",1000),new Metric("LOSS","报文丢失比例","%",20));
            default -> List.of(new Metric("TEMP","温度","℃",100),new Metric("VIB","振动速度","mm/s",12),new Metric("CURRENT","电流","A",250));
        };
    }
}
