package com.bproject.ehm.publicdata;

import com.bproject.ehm.asset.domain.model.*;
import com.bproject.ehm.monitoring.domain.model.*;
import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort.TelemetrySample;
import com.bproject.ehm.alarm.domain.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;

/** Same openGauss transaction as inbox APPLIED. Only uses the locally configured isolated tuple. */
final class PublicBusinessMapper {
    private final CommittedInboxStore db;private final PublicDataConfig c;
    PublicBusinessMapper(CommittedInboxStore db){this.db=db;c=db.config;}
    String apply(JsonNode event){
        String id=event.path("event_id").asText(),device=event.path("device_id").asText();var mapping=db.catalog.devices.get(device);
        if(mapping==null)return "IGNORED"; // wider shared reader token is NOT authority to import other devices
        var points=event.path("payload").path("points");
        if(!points.isArray()||points.size()>1000)throw new IllegalArgumentException("POINT_ARRAY_REQUIRED");
        var codes=new HashSet<String>();for(var p:points)if(!codes.add(p.path("point_code").asText()))throw new IllegalArgumentException("DUPLICATE_EVENT_POINT");
        Instant now=Instant.now(),source=time(event,"source_ts_ms"),observed=time(event,"observed_ts_ms");
        if(observed==null)throw new IllegalArgumentException("OBSERVED_TIMESTAMP_REQUIRED");
        String asset=OwnerCatalog.asset(device),component=asset+"-ACQUISITION";
        JsonNode existing=read("asset",asset);
        if(existing!=null&&existing.path("archived").asBoolean())throw new IllegalArgumentException("ARCHIVED_ASSET_REQUIRES_OPERATOR_REVIEW");
        insert("asset",asset,Asset.create(asset,mapping.name()+"【公共协议模拟】","公共模拟设备",c.regionId(),null,"待分配",now));
        insert("component",component,Component.create(component,asset,null,"公共协议采集组（非厂家部件树）","采集分组","待厂家确认","工程模拟映射","待确认","待确认","待确认",null,"模拟接入",now));
        // Register expected points, including those absent from this event, so partial data cannot look 100% complete.
        for(var definition:mapping.points().values())if(!definition.missingMapping()){
            String code=OwnerCatalog.point(device,definition.code());
            insert("measurement-point",code,MeasurementPoint.create(code,asset,component,definition.name(),definition.name(),
                definition.unit().isTextual()?definition.unit().asText():null,"PUBLIC_COMMITTED_SIM",definition.code(),5,null,null,definition.scalar(),now));
        }
        int mapped=0,unmapped=0,usable=0,scalarCount=(int)mapping.points().values().stream().filter(p->p.scalar()&&!p.missingMapping()).count();boolean explicitBad=false;
        for(var p:points){
            String original=p.path("point_code").asText();var definition=mapping.points().get(original);if(definition==null)continue;
            String code=OwnerCatalog.point(device,original);Instant pointSource=p.has("source_ts_ms")?time(p,"source_ts_ms"):source;
            var raw=new LinkedHashMap<String,Object>();raw.put("eventId",id);raw.put("deviceId",device);raw.put("assetCode",asset);raw.put("pointCode",code);raw.put("originalPointCode",original);
            raw.put("simulated",true);raw.put("rawPoint",p);raw.put("catalogUnit",definition.unit());raw.put("sourceTimestamp",pointSource);raw.put("observedAt",observed);raw.put("receivedAt",now);
            raw.put("mappingStatus",definition.missingMapping()?"PENDING_MANUFACTURER_MAPPING":"ENGINEERING_SIMULATION_NOT_OEM");
            String observationKey=CommittedInboxStore.hash((id+"\n"+original).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            insert("committed-observation",observationKey,raw); // strings, arrays, nulls and asset references stay lossless
            if(definition.missingMapping()){unmapped++;continue;}mapped++;
            var quality=p.get("quality");String qualityCode=quality!=null&&quality.isTextual()?quality.asText():null;
            boolean unitMatches=Objects.equals(p.get("unit"),definition.unit().isMissingNode()?null:definition.unit());
            boolean finite=p.path("value").isNumber()&&Double.isFinite(p.path("value").doubleValue());
            boolean good="GOOD".equals(qualityCode)&&finite&&unitMatches&&pointSource!=null;
            if(definition.scalar()&&good&&!pointSource.isBefore(now.minusSeconds(30))&&!pointSource.isAfter(now.plusSeconds(5)))usable++;
            explicitBad|=Set.of("BAD","STALE").contains(qualityCode==null?"":qualityCode);
            // A typed scalar time-series is separate from the lossless observation, never coerce booleans/strings/arrays to zero.
            if(definition.scalar())insert("committed-telemetry",observationKey,new TelemetrySample(code,asset,definition.name(),
                p.path("unit").isTextual()?p.path("unit").asText():null,finite?p.path("value").doubleValue():null,qualityCode,pointSource,now));
            JsonNode latest=read("committed-latest",code);
            if(newer(pointSource,observed,id,latest)){
                save("committed-latest",code,raw);
                if(definition.scalar()){
                    // Suppress untrusted source values in the evaluation view; raw/series preserve the exact value and source quality.
                    MeasurementPoint configured;
                    try{configured=db.json.treeToValue(read("measurement-point",code),MeasurementPoint.class);}catch(Exception ex){throw new IllegalArgumentException("POINT_CONFIGURATION_INVALID");}
                    var assessed=PointQualitySnapshot.assess(code,asset,configured.enabled()&&!configured.archived(),configured.sampleIntervalSeconds(),configured.lowerLimit(),configured.upperLimit(),good?p.path("value").doubleValue():null,pointSource,now,null,now);
                    save("point-quality",code,new PointQualitySnapshot(code,asset,assessed.status(),assessed.value(),pointSource,now,now,
                        "公共模拟源质量="+Objects.toString(qualityCode,"UNKNOWN")+"；"+(unitMatches?"":"单位不匹配；")+assessed.message(),assessed.consecutiveFailures(),null));
                }
            }
        }
        JsonNode previous=read("committed-device-position",asset);
        if(newer(source,observed,id,previous)){
            var position=new LinkedHashMap<String,Object>();position.put("eventId",id);position.put("sourceTimestamp",source);position.put("observedAt",observed);save("committed-device-position",asset,position);
            Double availability=scalarCount==0?null:Math.round(usable*1000.0/scalarCount)/10.0;
            JsonNode incident=read("committed-quality-incident",asset);
            if(explicitBad&&(incident==null||!incident.path("open").asBoolean())){
                String no="MQ-Q-"+CommittedInboxStore.hash((asset+id).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,24);
                insert("alarm",no,new Alarm(no,asset,mapping.name(),"公共采集链路","提示","info",
                    "公共模拟源报告BAD/STALE：请检查采集链路和时钟；不代表设备物理故障",AlarmStatus.NEW,"待人工核实","PUBLIC_SIM_DATA_QUALITY",observed,null,null,"待分配",List.of(),null));
            }
            save("committed-quality-incident",asset,Map.of("open",explicitBad,"eventId",id));
            save("device-snapshot",asset,DeviceSnapshot.initial(asset,"公共协议模拟接入",null,"待健康规则评估","limited",availability,
                "仅采集，不参与设备调度控制",explicitBad?"采集质量异常，待核实":"设备告警含义待确认",null,null,null,null,now));
        }
        insert("committed-business-receipt",id,Map.of("eventId",id,"assetCode",asset,"simulated",true,"mappedPoints",mapped,"pendingMappingPoints",unmapped,
            "healthConclusion","NOT_INFERRED_FROM_RAW_DEVICE_FIELDS","sharedResultPublished",false));
        return "APPLIED";
    }
    static Instant time(JsonNode n,String key){
        JsonNode v=n.get(key);if(v==null||v.isNull())return null;
        if(!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<0)throw new IllegalArgumentException("INVALID_EPOCH_MS");
        return Instant.ofEpochMilli(v.longValue());
    }
    private boolean newer(Instant source,Instant observed,String id,JsonNode previous){
        if(previous==null)return true;
        Instant oldSource=parse(previous.path("sourceTimestamp")),oldObserved=parse(previous.path("observedAt"));
        // An event lacking a source timestamp cannot replace a trustworthy sourced latest point.
        if(source==null&&oldSource!=null)return false;
        if(source!=null&&oldSource==null)return true;
        int order=(source==null?observed:source).compareTo(oldSource==null?oldObserved:oldSource);
        if(order!=0)return order>0;
        return id.compareTo(previous.path("eventId").asText())>0;
    }
    private Instant parse(JsonNode value){return value.isTextual()?Instant.parse(value.asText()):null;}
    JsonNode read(String type,String id){
        var rows=db.jdbc.queryForList("SELECT payload FROM "+c.aggregateTable()+" WHERE project_id=? AND region_id=? AND subsystem_code='EHMS' AND aggregate_type=? AND aggregate_id=?",String.class,c.projectId(),c.regionId(),type,id);
        if(rows.isEmpty())return null;try{return db.json.readTree(rows.get(0));}catch(Exception ex){throw new IllegalStateException("PERSISTED_BUSINESS_JSON_INVALID");}
    }
    void insert(String type,String id,Object value){if(read(type,id)==null)save(type,id,value);}
    void save(String type,String id,Object value){
        String payload=db.encode(value);Timestamp now=Timestamp.from(Instant.now());
        if(db.jdbc.update("UPDATE "+c.aggregateTable()+" SET payload=?,updated_at=? WHERE project_id=? AND region_id=? AND subsystem_code='EHMS' AND aggregate_type=? AND aggregate_id=?",payload,now,c.projectId(),c.regionId(),type,id)==0)
            db.jdbc.update("INSERT INTO "+c.aggregateTable()+" (project_id,region_id,subsystem_code,aggregate_type,aggregate_id,payload,updated_at) VALUES (?,?,'EHMS',?,?,?,?)",c.projectId(),c.regionId(),type,id,payload,now);
    }
}
