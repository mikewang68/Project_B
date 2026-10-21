package com.bproject.audit.service;

import com.bproject.audit.client.CanonicalJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Exact UTF-8 snapshot mapping. Never passes int64 through floating point or rewrites the inner timestamp. */
public final class GenericEvidenceMapping {
    private GenericEvidenceMapping() {}
    public static String request(ObjectMapper mapper,String key,String eventJson,String payloadHash) throws Exception {
        if(!key.matches("[0-9a-f]{64}")||!CanonicalJson.hash(eventJson).equals(payloadHash))throw new IllegalArgumentException("Invalid immutable event/hash");
        var event=mapper.readTree(eventJson);
        String expected=CanonicalJson.hash(event.path("domainId").asText()+"\n"+event.path("zoneId").asText()+"\n"+event.path("appCode").asText()+"\n"+event.path("eventId").asText());
        if(!key.equals(expected))throw new IllegalArgumentException("Event scope/key mismatch");
        Map<String,Object> request=new TreeMap<>();
        request.put("schemaVersion",1);request.put("sourceEventId",key);request.put("businessObjectId",key);
        request.put("eventType","OPERATION_LOG");
        request.put("occurredAt",Instant.parse(event.path("occurredAt").asText()).truncatedTo(ChronoUnit.MICROS).toString());
        request.put("payload",new TreeMap<>(Map.of("event",event,"payloadHash",payloadHash)));
        String text=mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(request)+"\n";
        if(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144)throw new IllegalArgumentException("Request too large");
        return text;
    }
    /** Schema-2 canonical matches TRUST's published initial-version mapping; not the AUDIT payload hash. */
    public static String eventHash(ObjectMapper mapper,String request,JsonNode receipt,TrustSources.Route route) throws Exception {
        var input=mapper.readTree(request);
        Map<String,Object> event=new TreeMap<>();input.fields().forEachRemaining(e->event.put(e.getKey(),e.getValue()));
        event.put("sourceSystem",route.sourceSystem());
        Map<String,Object> canonical=new TreeMap<>();
        canonical.put("schemaVersion",2);canonical.put("id",receipt.path("id").asText());
        canonical.put("rootId",receipt.path("rootId").asText());canonical.put("orgId",route.orgId());
        canonical.put("submittedBy","source:"+route.id());canonical.put("recordKind","GENERIC_V1");
        canonical.put("version",receipt.path("version").asInt());canonical.put("supersedesId",null);
        canonical.put("event",event);canonical.put("evidence",List.of());
        canonical.put("originalRequest",new TreeMap<>(Map.of("mediaType","application/json","sha256",CanonicalJson.hash(request),"sizeBytes",request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)));
        return CanonicalJson.hash(mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(canonical));
    }
}
