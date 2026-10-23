package com.bdemo.auth.sso;

import com.bdemo.system.MenuItem;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Limits only SSO sessions. Unimplemented area-scoped APIs stay closed instead of leaking data. */
public final class EmcsSsoPolicy {
    private EmcsSsoPolicy(){}
    public static String requiredPermission(String path){
        if(path.startsWith("/overview/"))return "energy:overview:view";
        if(path.equals("/equipment-profiles") || path.startsWith("/equipment-profiles/"))return "energy:analysis:profile";
        if(path.equals("/alerts") || path.startsWith("/alerts/"))return "energy:alert:list";
        if(path.equals("/suggestions") || path.startsWith("/suggestions/"))return "energy:alert:suggestion";
        if(path.startsWith("/raw-quality/"))return "energy:collect:raw:view";
        if(path.startsWith("/cost/"))return "energy:cost:record";
        if(path.startsWith("/reports/"))return "energy:cost:report";
        if(path.startsWith("/agent/"))return "energy:agent:inspection";
        return null;
    }
    public static boolean allowed(EmcsSsoAccess access,String method,String path){
        if("POST".equals(method) && "/logout".equals(path))return true;
        boolean read="GET".equals(method) || "HEAD".equals(method);
        if(!read && access.effectiveReadOnly() && !("POST".equals(method) && "/reports/preview".equals(path) && !access.areaRestricted()))return false;
        if(access.effectiveReadOnly() && (path.endsWith("/export") || path.contains("/export/")))return false;
        if(Set.of("/getInfo","/getRouters").contains(path))return read;
        boolean scoped=path.equals("/overview/summary") || path.equals("/equipment-profiles")
                || path.matches("/equipment-profiles/[^/]+") || path.equals("/alerts")
                || path.equals("/alerts/statistics") || path.matches("/alerts/[0-9]+");
        if(access.areaRestricted())return read && scoped;
        return scoped || path.startsWith("/alerts/") || path.equals("/suggestions") || path.startsWith("/suggestions/")
                || path.startsWith("/raw-quality/") || path.startsWith("/cost/") || path.startsWith("/reports/") || path.startsWith("/agent/");
    }
    public static List<MenuItem> menus(List<MenuItem> all,EmcsSsoAccess access){
        Set<Long> keep=new HashSet<>(access.areaRestricted()?Set.of(2001L,2004L,2010L):Set.of(2001L,2004L,2008L,2010L,2011L,2013L,2014L,2015L));
        keep.retainAll(all.stream().filter(m->"0".equals(m.visible())).map(MenuItem::menuId).toList());
        boolean changed;
        do {changed=false;for(MenuItem m:all)if(keep.contains(m.menuId()) && m.parentId()!=0)changed|=keep.add(m.parentId());}while(changed);
        return all.stream().filter(m->keep.contains(m.menuId()) && "0".equals(m.visible())).toList();
    }
}
