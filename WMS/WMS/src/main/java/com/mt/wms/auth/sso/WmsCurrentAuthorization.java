package com.mt.wms.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.mt.wms.auth.WmsPrincipal;
import java.util.HashSet;
import java.util.Set;

/** Intersect current IAM grants with freshly loaded local business grants. */
public final class WmsCurrentAuthorization {
    private WmsCurrentAuthorization() {}
    public static WmsPrincipal apply(WmsPrincipal local, JsonNode current) {
        if (!current.path("readOnly").isBoolean() || !current.path("permissions").isArray())
            throw new SecurityException("Missing current IAM grants");
        boolean readOnly=current.path("readOnly").asBoolean();
        boolean full=current.path("superAdmin").asBoolean(false)&&!readOnly;
        boolean globalRead=readOnly&&"READONLY_AUDITOR".equals(current.path("accessMode").asText());
        Set<String> central=new HashSet<>();current.path("permissions").forEach(v->central.add(v.asText()));
        Set<String> permissions=new HashSet<>(local.permissions());
        if (!full && !globalRead) permissions.removeIf(p->!central.contains(p)&&!central.contains("wms:"+p));
        if (readOnly) permissions.removeIf(p->!p.endsWith(":read")&&!p.endsWith(":view")&&!p.equals("tenant:all"));
        return new WmsPrincipal(local.userId(),local.companyId(),local.companyCode(),local.companyName(),
                local.username(),local.displayName(),local.roles(),Set.copyOf(permissions),local.passwordChangeRequired());
    }
}
