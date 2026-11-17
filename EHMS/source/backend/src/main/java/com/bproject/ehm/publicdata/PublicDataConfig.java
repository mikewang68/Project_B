package com.bproject.ehm.publicdata;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;

/** Independent development worker. Never instantiated or scheduled by the EHMS web application. */
public record PublicDataConfig(boolean enabled,String jdbcUrl,String dbUser,Path dbPasswordFile,
        String schema,String table,String sourceId,String projectId,String regionId,String principal,
        String policyRevision,String httpBase,Path httpTokenFile,Path catalogFile,Path allocationFile) {
    public PublicDataConfig {
        for(String id:List.of(schema,table)) if(!id.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))
            throw new IllegalArgumentException("UNSAFE_SQL_IDENTIFIER");
        for(String id:List.of(sourceId,projectId,regionId,principal,policyRevision))
            if(!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))throw new IllegalArgumentException("INVALID_IDENTITY");
        if(regionId.equals("ALL")||regionId.equals("UNASSIGNED")||regionId.equals("*")||!jdbcUrl.startsWith("jdbc:"))
            throw new IllegalArgumentException("EXPLICIT_ISOLATED_SCOPE_REQUIRED");
        var uri=java.net.URI.create(httpBase);
        if(!"http".equals(uri.getScheme())||!"127.0.0.1".equals(uri.getHost())||uri.getPort()<1
                ||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!Set.of("","/").contains(uri.getPath()))
            throw new IllegalArgumentException("SSH_LOOPBACK_HTTP_ONLY");
    }
    public static PublicDataConfig load(Path path,ObjectMapper json)throws Exception {
        JsonNode n=json.readTree(Files.readAllBytes(path));Path base=path.toAbsolutePath().getParent();
        return new PublicDataConfig(n.path("enabled").asBoolean(false),text(n,"jdbcUrl"),text(n,"dbUser"),
            file(base,n,"dbPasswordFile"),text(n,"schema"),text(n,"table"),text(n,"sourceId"),text(n,"projectId"),
            text(n,"regionId"),text(n,"principal"),text(n,"policyRevision"),text(n,"httpBase"),file(base,n,"httpTokenFile"),
            file(base,n,"catalogFile"),file(base,n,"allocationFile"));
    }
    private static String text(JsonNode n,String k){String s=n.path(k).asText();if(s.isBlank())throw new IllegalArgumentException("CONFIG_FIELD_REQUIRED: "+k);return s;}
    private static Path file(Path b,JsonNode n,String k){return b.resolve(text(n,k)).normalize();}
    public String aggregateTable(){return schema+"."+table;}
    public String auxiliary(String name){if(!Set.of("consumer","inbox","quarantine","cursor").contains(name))throw new IllegalArgumentException();return schema+".ehms_pd_"+name;}
}
