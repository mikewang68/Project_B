package com.bproject.audit.service;

import com.bproject.audit.client.CanonicalJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** One exact domain/zone/app per permanent TRUST source. No wildcard fallback. Tokens never enter receipts. */
public final class TrustSources {
    public record Route(String id,String domainId,String zoneId,String appCode,String orgId,String sourceSystem,
                        String baseUrl,String tokenFile,boolean enabled,boolean archiveApproved,boolean retryAllowed,
                        String eligibleFrom,String instanceId) {
        public boolean matches(String domain,String zone,String app){return domainId.equals(domain)&&zoneId.equals(zone)&&appCode.equals(app);}
        public String fingerprint(){return CanonicalJson.hash(String.join("\n",id,domainId,zoneId,appCode,orgId,sourceSystem,baseUrl,instanceId));}
        public String token() throws Exception {
            String token=Files.readString(Path.of(tokenFile)).trim();
            if(token.length()<16||token.length()>4096||token.chars().anyMatch(c->c<33||c>126))throw new IllegalArgumentException("Invalid TRUST credential file");
            return token;
        }
    }
    private final List<Route> routes;
    public TrustSources(ObjectMapper mapper,String file) throws Exception {
        routes=file.isBlank()?List.of():mapper.readValue(Files.readString(Path.of(file)),new TypeReference<List<Route>>(){});
        Set<String> scopes=new HashSet<>(),ids=new HashSet<>(),names=new HashSet<>(),tokens=new HashSet<>();
        for(Route r:routes){
            for(String value:List.of(r.id(),r.domainId(),r.zoneId(),r.appCode(),r.orgId(),r.sourceSystem(),r.instanceId()))
                if(value.isBlank()||!value.matches("[A-Za-z0-9_.:-]{1,128}")||value.equals("*"))throw new IllegalArgumentException("Invalid source identity/scope");
            URI uri=URI.create(r.baseUrl());
            if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!uri.getPath().equals("/api/v1/integrations/evidence/events"))throw new IllegalArgumentException("Invalid exact TRUST endpoint");
            Instant.parse(r.eligibleFrom());
            if(!ids.add(r.id())||!scopes.add(r.domainId()+"\n"+r.zoneId()+"\n"+r.appCode())||!names.add(r.orgId()+"\n"+r.sourceSystem()))throw new IllegalArgumentException("Duplicate source/scope/namespace");
            if(r.enabled()&&r.archiveApproved()&&!tokens.add(CanonicalJson.hash(r.token())))throw new IllegalArgumentException("Source credentials must not be shared");
        }
    }
    public List<Route> active(){return routes.stream().filter(r->r.enabled()&&r.archiveApproved()).toList();}
    public Route find(String domain,String zone,String app){return active().stream().filter(r->r.matches(domain,zone,app)).findFirst().orElse(null);}
}
