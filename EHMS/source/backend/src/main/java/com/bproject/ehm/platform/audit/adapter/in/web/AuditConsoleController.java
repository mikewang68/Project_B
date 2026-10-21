package com.bproject.ehm.platform.audit.adapter.in.web;

import com.bproject.ehm.platform.audit.application.AuditConsoleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @Profile("server") @RequestMapping("/api/ehm/v1/system")
public class AuditConsoleController {
    private final AuditConsoleService console;
    public AuditConsoleController(AuditConsoleService console){this.console=console;}
    @GetMapping("/audit-session") public Map<String,Object> session(HttpServletRequest request){return console.session(request);}
    @GetMapping("/audit-events") public Map<String,Object> list(HttpServletRequest request,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size,
            @RequestParam(required=false) String action,@RequestParam(required=false) String outcome,
            @RequestParam(required=false) String delivery,@RequestParam(required=false) String userId,
            @RequestParam(required=false) String eventId,@RequestParam(required=false) String traceId,
            @RequestParam(required=false) String from,@RequestParam(required=false) String to){
        return console.query(request,page,size,action,outcome,delivery,userId,eventId,traceId,from,to);
    }
    @GetMapping("/audit-events/{id}") public Map<String,Object> detail(HttpServletRequest request,@PathVariable String id){return console.detail(request,id);}
    @GetMapping("/audit-events/{id}/receipt") public Map<String,Object> receipt(HttpServletRequest request,@PathVariable String id){return console.receipt(request,id);}
}
