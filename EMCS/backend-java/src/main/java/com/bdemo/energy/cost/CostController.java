package com.bdemo.energy.cost;

import com.bdemo.auth.AuthenticatedUser;
import com.bdemo.common.AjaxResult;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/cost")
public class CostController {
    private final CostService service;
    public CostController(CostService service) { this.service = service; }

    // REQ-051~056/062: cost views always read immutable priced records and their snapshots.
    @GetMapping("/month-view")
    public AjaxResult monthView(@RequestParam(required = false) String statMonth,
                                @RequestParam(defaultValue = "ALL") String zone,
                                @RequestParam(defaultValue = "electricity") String energyType,
                                @RequestParam(defaultValue = "area") String groupBy,
                                @RequestParam(required = false) String focus) {
        return AjaxResult.success(service.monthView(statMonth, zone, energyType, groupBy, focus)).add("msg", "成本月度视图");
    }
    @GetMapping("/trace")
    public AjaxResult trace(@RequestParam String statMonth, @RequestParam String objectType,
                            @RequestParam(required = false) Long objectId, @RequestParam String energyType,
                            @RequestParam(required = false) String costVersion) {
        return AjaxResult.success(service.trace(statMonth, objectType, objectId, energyType, costVersion)).add("msg", "成本反查证据");
    }
    @GetMapping("/tariffs") public AjaxResult tariffs(@RequestParam(required = false) String energyType,
            @RequestParam(required = false) String effectiveOn, @RequestParam(defaultValue = "false") boolean includeHistory) {
        return AjaxResult.success(Map.of("items", service.tariffs(energyType, effectiveOn, includeHistory))).add("msg", "单价版本");
    }
    @PostMapping("/tariffs") public AjaxResult createTariff(@RequestBody Map<String,Object> body,
            @AuthenticationPrincipal AuthenticatedUser user) { return AjaxResult.success(service.createTariff(body, user.userName())).add("msg", "单价版本已新增，尚未触发重算"); }
    @GetMapping("/allocation-rules") public AjaxResult allocationRules(@RequestParam(required = false) String scope,
            @RequestParam(required = false) String effectiveOn, @RequestParam(defaultValue = "false") boolean includeHistory) {
        return AjaxResult.success(Map.of("items", service.allocationRules(scope, effectiveOn, includeHistory))).add("msg", "分摊规则版本");
    }
    @PostMapping("/allocation-rules") public AjaxResult createAllocation(@RequestBody Map<String,Object> body,
            @AuthenticationPrincipal AuthenticatedUser user) { return AjaxResult.success(service.createAllocation(body, user.userName())).add("msg", "分摊规则版本已新增"); }
    @GetMapping("/recomputations") public AjaxResult recomputations(@RequestParam(required = false) String statMonth,
            @RequestParam(required = false) String energyType, @RequestParam(required = false) String reviewStatus,
            @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize) {
        return AjaxResult.success(service.recomputations(statMonth, energyType, reviewStatus, pageNum, pageSize)).add("msg", "成本重算记录");
    }
    @GetMapping("/recomputations/{id}") public AjaxResult recomputation(@PathVariable long id) { return AjaxResult.success(service.recomputation(id)).add("msg", "成本重算详情"); }
    @PostMapping("/recomputations") public AjaxResult recompute(@RequestBody Map<String,Object> body,
            @AuthenticationPrincipal AuthenticatedUser user) { return AjaxResult.success(service.recompute(body, user.userName())).add("msg", "成本重算已发起，等待财务复核"); }
    @PostMapping("/recomputations/{id}/review") public AjaxResult review(@PathVariable long id, @RequestBody Map<String,Object> body,
            @AuthenticationPrincipal AuthenticatedUser user) { return AjaxResult.success(service.review(id, body, user.userName())).add("msg", "成本重算复核完成"); }
}
