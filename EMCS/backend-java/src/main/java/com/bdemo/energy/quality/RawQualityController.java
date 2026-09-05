package com.bdemo.energy.quality;

import com.bdemo.auth.AuthenticatedUser;
import com.bdemo.common.AjaxResult;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/raw-quality")
public class RawQualityController {
    private final RawQualityService service;

    public RawQualityController(RawQualityService service) { this.service = service; }

    // REQ-010~023/062: one consistent read model for raw readings, collection, quality and recompute evidence.
    @GetMapping("/summary")
    public AjaxResult summary(@RequestParam(required = false) String pointId,
                              @RequestParam(required = false) String timeStart,
                              @RequestParam(required = false) String timeEnd,
                              @RequestParam(defaultValue = "ALL") String zone) {
        return AjaxResult.success(service.summary(pointId, timeStart, timeEnd, zone)).add("msg", "raw-quality summary");
    }

    // REQ-011/014: idempotent point+time backfill; dryRun never writes.
    @PostMapping("/backfill")
    public AjaxResult backfill(@RequestBody BackfillRequest body,
                               @AuthenticationPrincipal AuthenticatedUser user) {
        return AjaxResult.success(service.backfill(body, user.userName())).add("msg", "backfill 完成");
    }

    // REQ-012/020: consume the pending derived-period hint and refresh the aggregate version.
    @PostMapping("/recompute")
    public AjaxResult recompute(@RequestBody RecomputeRequest body,
                                @AuthenticationPrincipal AuthenticatedUser user) {
        return AjaxResult.success(service.recompute(body, user.userName())).add("msg", "recompute 完成");
    }

    public record BackfillRequest(String pointId, String cacheStart, String cacheEnd, Boolean dryRun) {}
    public record RecomputeRequest(String periodKey, String scope) {}
}
