package com.bproject.audit.service;

import com.bproject.audit.client.LogEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController @RequestMapping("/api/logs/v1")
public class LogController {
    private final LogStore store;
    private final org.springframework.beans.factory.ObjectProvider<GenericEvidenceWorker> generic;
    public LogController(LogStore store,org.springframework.beans.factory.ObjectProvider<GenericEvidenceWorker> generic) { this.store = store; this.generic=generic; }
    private ServiceIdentity identity(HttpServletRequest request, String permission) {
        var client = (ServiceIdentity) request.getAttribute(ServiceAuthFilter.IDENTITY);
        if (!client.can(permission)) {
            store.access(client, "READ", "DENIED", request.getRequestURI());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED");
        }
        return client;
    }
    @PostMapping("/events")
    public ResponseEntity<?> append(@RequestBody LogEvent event, HttpServletRequest request) {
        return ResponseEntity.accepted().body(store.append(event, identity(request, "WRITE")));
    }
    @GetMapping("/whoami")
    public Map<String,Object> whoami(HttpServletRequest request) {
        var client=identity(request,"READ");
        store.access(client,"READ","SUCCESS",request.getRequestURI());
        return Map.of("id",client.id(),"appCode",client.appCode(),"domainId",client.domainId(),"zoneId",client.zoneId(),"permissions",client.permissions());
    }
    @GetMapping("/events")
    public Map<String,Object> list(HttpServletRequest request, @RequestParam(required=false) String appCode,
                                   @RequestParam(required=false) String action, @RequestParam(required=false) String userId,
                                   @RequestParam(required=false) String from, @RequestParam(required=false) String to,
                                   @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="30") int size) {
        var client = identity(request, "READ");
        var result = store.query(client, appCode, action, userId, from, to, page, size);
        store.access(client, "READ", "SUCCESS", request.getRequestURI());
        return result;
    }
    @GetMapping("/events/{id}/receipt")
    public Map<String,Object> receipt(@PathVariable String id, @RequestParam(required=false) String appCode, HttpServletRequest request) {
        var client = identity(request, "READ");
        var result = store.receipt(client, id, appCode); store.access(client, "READ", "SUCCESS", request.getRequestURI()); return result;
    }
    @PostMapping("/events/{id}/retry")
    public Map<String,Object> retry(@PathVariable String id, @RequestParam(required=false) String appCode, HttpServletRequest request) {
        var client = identity(request, "RETRY");
        var worker=generic.getIfAvailable();
        if(worker!=null)worker.retry(client,id,appCode);else store.retry(client, id, appCode);
        store.access(client, "EXECUTE", "SUCCESS", request.getRequestURI());
        return store.receipt(client, id, appCode);
    }
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required=false) String appCode, HttpServletRequest request) {
        var client = identity(request, "EXPORT");
        StringBuilder csv = new StringBuilder("\uFEFFeventId,occurredAt,appCode,userId,menuId,action,outcome,chainState\r\n");
        // Bounded export. Use page queries for larger archival jobs rather than unbounded memory allocation.
        var result = store.query(client, appCode, null, null, null, null, 0, 100);
        for (var row : (List<Map<String,Object>>) result.get("items")) {
            String state = String.valueOf(((Map<?,?>) row.get("receipt")).get("chainState"));
            String[] cells = {String.valueOf(row.get("eventId")), String.valueOf(row.get("occurredAt")), String.valueOf(row.get("appCode")), String.valueOf(row.get("userId")), String.valueOf(row.get("menuId")), String.valueOf(row.get("action")), String.valueOf(row.get("outcome")), state};
            csv.append(java.util.Arrays.stream(cells).map(LogController::csv).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        }
        store.access(client, "EXPORT", "SUCCESS", request.getRequestURI());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).header("Content-Disposition", "attachment; filename=audit-latest-100.csv").body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String csv(String value) {
        if (value.matches("^[=+@\\-\\t\\r\\n].*")) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
