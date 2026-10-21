package com.bproject.audit.client;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/** IAM/SYS immutable local audit rows → public contract. Never forwards raw detail/password data. */
public final class LegacyLogMapper {
    private LegacyLogMapper() {}
    public static LogEvent map(Map<String,Object> row,String app,String domain,String zone,ZoneId sourceZone) {
        Object timestamp=row.get("created_at");
        Instant occurred=timestamp instanceof Timestamp t ? t.toLocalDateTime().atZone(sourceZone).toInstant()
                : timestamp instanceof LocalDateTime t ? t.atZone(sourceZone).toInstant()
                : timestamp instanceof Instant t ? t : null;
        if(occurred==null)throw new IllegalArgumentException("Historical audit requires immutable timestamp");
        String actor=text(row,"user_id");
        String action=Map.ofEntries(Map.entry("add","CREATE"),Map.entry("edit","UPDATE"),Map.entry("delete","DELETE"),Map.entry("login","LOGIN"),Map.entry("logout","LOGOUT"),Map.entry("query","READ"),Map.entry("view","READ"),Map.entry("export","EXPORT"),Map.entry("backup","BACKUP"),Map.entry("restore","RESTORE"),Map.entry("upload","UPLOAD"),Map.entry("download","DOWNLOAD")).getOrDefault(text(row,"action").trim().toLowerCase(java.util.Locale.ROOT),"EXECUTE");
        String canonicalAction=text(row,"action").trim().toUpperCase(java.util.Locale.ROOT);
        if(LogEvent.ACTIONS.contains(canonicalAction))action=canonicalAction;
        String path=text(row,"request_uri").split("\\?",2)[0];
        return new LogEvent(1,app+"-"+text(row,"id"),occurred,app,domain,zone,actor.isEmpty()?"anonymous":actor,actor.isEmpty()?"ANONYMOUS":"AUTHENTICATED",text(row,"module"),action,"",outcome(row),text(row,"request_method"),path,"",row.get("duration_ms") instanceof Number n?Math.max(0,n.longValue()):0,"REQUEST","已持久化本地审计转发；不转发原始详情");
    }
    static String outcome(Map<String,Object> row) {
        String result=text(row,"result").trim().toLowerCase(java.util.Locale.ROOT);
        int code=0;
        for(String key:new String[]{"http_status","status_code"}) {
            String value=text(row,key).trim();
            if(value.matches("[1-5][0-9]{2}")) { int parsed=Integer.parseInt(value); if(parsed==401||parsed==403)return "DENIED"; code=Math.max(code,parsed); }
        }
        if(result.matches("(?:401|403)(?:\\b.*)?")||java.util.Set.of("denied","forbidden","unauthorized").contains(result))return "DENIED";
        if(code>=400||java.util.Set.of("fail","failed","failure","error").contains(result)||result.matches("[45][0-9]{2}(?:\\b.*)?"))return "FAILED";
        if(result.equals("started"))return "STARTED";
        if(java.util.Set.of("success","succeeded","successful","ok").contains(result)||code>=200&&code<400||result.matches("[23][0-9]{2}"))return "SUCCESS";
        throw new IllegalArgumentException("Unknown legacy result; explicit mapping required");
    }
    private static String text(Map<String,Object> row,String key){return row.get(key)==null?"":String.valueOf(row.get(key));}
}
