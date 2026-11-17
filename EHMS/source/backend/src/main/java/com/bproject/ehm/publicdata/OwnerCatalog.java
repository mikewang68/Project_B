package com.bproject.ehm.publicdata;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;

/** Intersect the recipient's allocation with the observed public catalog. No name-based merging. */
public final class OwnerCatalog {
    public record Point(String code,String name,JsonNode unit,boolean missingMapping,boolean scalar,String wireType){}
    public record Device(String id,String name,Map<String,Point> points){}
    public final Map<String,Device> devices;
    public final int requiredPoints;
    public final String fingerprint;
    public OwnerCatalog(Path catalog,Path allocation,ObjectMapper json)throws Exception {
        this(json.readTree(Files.readAllBytes(catalog)),json.readTree(Files.readAllBytes(allocation)),json);
    }
    public OwnerCatalog(JsonNode catalog,JsonNode allocation,ObjectMapper json)throws Exception {
        if(!"EHMS".equals(allocation.path("system").asText())||!catalog.path("simulated").asBoolean()
                ||!"b.device-catalog.v1".equals(catalog.path("schema_version").asText()))throw new IllegalArgumentException("OWNER_CATALOG_REJECTED");
        var allowed=new HashSet<String>();
        for(var row:allocation.path("tables").path("设备采集点")){
            String code=row.path("点位编号").asText();
            if(!code.isBlank()&&Arrays.asList(row.path("原始点消费系统").asText().split("[；;,，]"))
                    .stream().map(String::trim).anyMatch("EHMS"::equals))allowed.add(code);
        }
        requiredPoints=allowed.size();var found=new LinkedHashMap<String,Device>();
        for(var device:catalog.path("devices")){
            String id=device.path("device_id").asText();if(!id.matches("SIM-[a-zA-Z0-9-]{1,64}"))throw new IllegalArgumentException("INVALID_DEVICE_ID");
            var points=new LinkedHashMap<String,Point>();
            for(var p:device.path("fields")){
                String code=p.path("point_code").asText();if(!allowed.contains(code))continue;
                if(!code.matches("[a-zA-Z0-9_-]{1,64}"))throw new IllegalArgumentException("INVALID_POINT_CODE");
                String type=p.path("provenance").path("wire_type").asText("");
                boolean scalar=Set.of("f32","f64","u16","u32","u64","i16","i32","i64","float32","float64").contains(type);
                if(points.put(code,new Point(code,p.path("name").asText(code),p.path("unit"),p.path("missing_mapping").asBoolean(),scalar,type))!=null)
                    throw new IllegalArgumentException("DUPLICATE_CATALOG_POINT");
            }
            if(!points.isEmpty()&&found.put(id,new Device(id,device.path("name").asText(id),Map.copyOf(points)))!=null)
                throw new IllegalArgumentException("DUPLICATE_CATALOG_DEVICE");
        }
        if(found.isEmpty())throw new IllegalArgumentException("EMPTY_OWNER_ALLOCATION");devices=Map.copyOf(found);
        fingerprint=CommittedInboxStore.hash(json.writeValueAsBytes(List.of(catalog,allocation)));
    }
    public static String asset(String device){return "MQ-"+device.toUpperCase(Locale.ROOT);}
    public static String point(String device,String code){return asset(device)+"-"+code.toUpperCase(Locale.ROOT);}
}
