package com.bproject.ehm.platform.audit.adapter.in.web;

import com.bproject.ehm.platform.audit.application.AuditApplicationService;
import com.bproject.ehm.platform.audit.domain.model.AuditRecord;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/ehm/v1/system/audit-logs")
public class AuditController {
    private final AuditApplicationService audit;
    private final org.springframework.beans.factory.ObjectProvider<com.bproject.ehm.platform.audit.application.AuditConsoleService> console;

    public AuditController(AuditApplicationService audit,org.springframework.beans.factory.ObjectProvider<com.bproject.ehm.platform.audit.application.AuditConsoleService> console) {
        this.audit = audit;this.console=console;
    }

    @GetMapping
    public List<AuditRecord> latest(@RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit,jakarta.servlet.http.HttpServletRequest request) {
        var actor=IamAuditIdentityFilter.actor(request);
        if(actor==null)return List.of();
        var viewer=console.getIfAvailable();
        return audit.latest(limit).stream().filter(row->(viewer!=null&&viewer.all(request))||actor.getName().equals(row.operator())).toList();
    }
}
