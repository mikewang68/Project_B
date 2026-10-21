package com.bproject.ehm.platform.audit.application;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import java.util.Map;
import java.util.Set;

/** Route metadata only: never reads bodies, query strings, credentials or AI messages. */
public final class AuditRequestMetadata {
    private static final String CREATED_OBJECT=AuditRequestMetadata.class.getName()+".createdObject";
    private AuditRequestMetadata() {}
    private static final Set<String> COMMANDS = Set.of("run", "replay", "close", "approve", "publish", "apply",
            "acknowledge", "complete", "disable", "issue", "release", "review", "verify", "test", "chat",
            "batch-assign", "generate", "submit", "receive", "receipt", "return", "start", "diagnoses",
            "execution-records", "retests", "inspection-tasks", "work-order", "case", "spare-reservations");
    public static String action(String method, String path) {
        if(path.startsWith("/api/ehm/v1/auth/")&&path.endsWith("/password")&&method.equals("POST"))return "UPDATE";
        if (path.matches(".*/(export|download)(/.*)?")) return "EXPORT";
        if (path.matches(".*/(backup|restore)(/.*)?")) return path.contains("/restore") ? "RESTORE" : "BACKUP";
        if (path.endsWith("/login")) return "LOGIN";
        if (path.endsWith("/logout")) return "LOGOUT";
        if (method.equals("GET")) return "READ";
        if (method.equals("DELETE")) return "DELETE";
        if (method.equals("PUT") || method.equals("PATCH")) return "UPDATE";
        String tail = path.substring(path.lastIndexOf('/') + 1);
        // Collection creation remains CREATE; nested lifecycle commands are EXECUTE.
        if (COMMANDS.contains(tail) && (path.endsWith("/chat") || path.endsWith("/test")
                || path.endsWith("/batch-assign") || path.endsWith("/generate")
                || path.replaceFirst("^/api/ehm/v1/?", "").split("/").length >= 3)) return "EXECUTE";
        return method.equals("POST") ? "CREATE" : "EXECUTE";
    }
    public static String objectId(HttpServletRequest request) {
        if(request.getAttribute(CREATED_OBJECT) instanceof String created)return created;
        if (!(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> vars)) return "";
        // Prefer the target resource over its parent (e.g. a component under an asset).
        for (String key : new String[]{"componentCode", "reservationNo", "defectNo", "taskNo", "orderNo",
                "alarmNo", "handoverNo", "partCode", "caseNo", "recordNo", "changeNo", "templateCode",
                "assessmentId", "pointCode", "planId", "severity", "code", "id", "assetCode"}) {
            Object value = vars.get(key);
            if (value instanceof String text) return clean(text, 192);
        }
        return "";
    }
    /** Read only a whitelisted identifier accessor, never serialize the business response/body. */
    public static void createdObject(HttpServletRequest request,Object result) {
        if(!request.getMethod().equals("POST"))return;
        Object body=result instanceof org.springframework.http.ResponseEntity<?> response?response.getBody():result;
        if(body==null||!body.getClass().isRecord()||!body.getClass().getPackageName().startsWith("com.bproject.ehm."))return;
        for(String name:new String[]{"code","orderNo","alarmNo","handoverNo","taskNo","recordNo","changeNo","templateCode","caseNo","reservationNo","id"}){
            try{
                Object value=body.getClass().getMethod(name).invoke(body);
                if(value instanceof String text&&!text.isBlank()){request.setAttribute(CREATED_OBJECT,clean(text,192));return;}
            }catch(ReflectiveOperationException ignored){/* Not this response's identifier accessor. */}
        }
    }
    public static String route(HttpServletRequest request) {
        Object template = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        // Unknown/rejected routes retain only the API category, not arbitrary attacker input.
        if (template instanceof String path) return clean(path, 256);
        String[] segments = request.getRequestURI().replaceFirst("^/api/ehm/v1/?", "").split("/");
        String category = segments.length > 0 && segments[0].matches("[a-z][a-z0-9-]{0,64}") ? segments[0] : "unknown";
        return "/api/ehm/v1/" + category + (segments.length > 1 ? "/{unresolved}" : "");
    }
    static String clean(String text, int limit) {
        String clean = text.replaceAll("[\\p{Cntrl}]", "");
        return clean.substring(0, Math.min(clean.length(), limit));
    }
}
