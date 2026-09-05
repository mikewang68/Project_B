package com.bdemo.energy.pipeline;

import com.bdemo.common.AjaxResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/pipeline")
public class PipelineController {
    private final PipelineService service;

    public PipelineController(PipelineService service) { this.service = service; }

    @PostMapping("/aggregation/rebuild-all")
    public AjaxResult rebuildAll() { return AjaxResult.success(service.rebuildAll()).add("msg", "聚合重跑完成"); }

    @PostMapping("/aggregation/rebuild-range")
    public AjaxResult rebuildRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return AjaxResult.success(service.rebuildRange(start, end)).add("msg", "聚合重跑完成");
    }

    @PostMapping("/baseline/publish")
    public AjaxResult baseline(@RequestParam(defaultValue = "false") boolean forceRepublish) {
        return AjaxResult.success(service.baselineStatus(forceRepublish)).add("msg", "基线发布检查完成");
    }

    @PostMapping("/rules/run")
    public AjaxResult rules() { return AjaxResult.success(service.ruleStatus()).add("msg", "规则评估完成"); }

    @PostMapping("/cost/rebuild")
    public AjaxResult cost() { return AjaxResult.success(service.costIntegrity()).add("msg", "成本完整性检查完成"); }

    @PostMapping("/bootstrap")
    public AjaxResult bootstrap(@RequestParam(defaultValue = "false") boolean forceRepublish) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("aggregation", service.rebuildAll());
        data.put("baseline_publish", service.baselineStatus(forceRepublish));
        data.put("cost_initialize", service.costIntegrity());
        data.put("rules", service.ruleStatus());
        return AjaxResult.success(data).add("msg", "bootstrap 完成");
    }
}
