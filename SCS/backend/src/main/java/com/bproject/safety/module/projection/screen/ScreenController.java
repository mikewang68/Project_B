package com.bproject.safety.module.projection.screen;

import com.bproject.safety.module.projection.screen.ScreenDtos.ScreenCritical;
import com.bproject.safety.module.projection.screen.ScreenDtos.ScreenFeedItem;
import com.bproject.safety.module.projection.screen.ScreenDtos.ScreenOverview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 安全大屏只读接口（聚合自 Alert 权威源，无独立数据表）。
 */
@RestController
@RequestMapping("/api/v1/screen")
@Tag(name = "安全大屏投影", description = "大屏总览 / 事件流 / 紧急事件，只读聚合")
public class ScreenController {

    private final ScreenService screenService;

    public ScreenController(ScreenService screenService) {
        this.screenService = screenService;
    }

    @GetMapping("/overview")
    @Operation(summary = "大屏核心指标与分布（设备/在岗为 Demo 聚合，告警为真实统计）")
    public ScreenOverview overview() {
        return screenService.overview();
    }

    @GetMapping("/alerts/feed")
    @Operation(summary = "最近安全事件流")
    public List<ScreenFeedItem> feed(@RequestParam(defaultValue = "12") int limit) {
        return screenService.feed(limit);
    }

    @GetMapping("/critical")
    @Operation(summary = "当前紧急且未关闭的最高优先级事件；无则返回 null")
    public ScreenCritical critical() {
        return screenService.critical();
    }
}
