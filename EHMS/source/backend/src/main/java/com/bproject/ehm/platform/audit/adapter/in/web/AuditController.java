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

    public AuditController(AuditApplicationService audit) {
        this.audit = audit;
    }

    @GetMapping
    public List<AuditRecord> latest(@RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return audit.latest(limit);
    }
}
