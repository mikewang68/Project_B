package com.bproject.audit.service;
import java.util.Set;
public record ServiceIdentity(String id, String token, String appCode, String domainId, String zoneId, Set<String> permissions) {
    public boolean can(String action) { return permissions.contains(action); }
}
