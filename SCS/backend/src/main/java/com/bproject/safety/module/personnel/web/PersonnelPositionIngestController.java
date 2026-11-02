package com.bproject.safety.module.personnel.web;

import com.bproject.safety.module.personnel.service.PersonnelPositionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人员实时位置上报（UWB 定位引擎经网关上报，X-API-Key 机器凭据）。
 *
 * <p>与 {@code /api/v1/ingest/device-state}、{@code /api/v1/ingest/zone-state} 同一前缀：
 * {@code SecurityInterceptor} 只允许机器凭据访问该前缀，校验通过后直接放行，不进浏览器会话的权限分支。</p>
 *
 * <p>坐标口径是首页底图的百分比（0~100），不是米制站场坐标——米制坐标到百分比的换算
 * 属于现场坐标系文件的一部分，由网关按实际站场尺寸完成，后端只接收归一化后的位置。</p>
 */
@RestController
@RequestMapping("/api/v1/ingest")
@Tag(name = "人员位置接入", description = "UWB 标签位置经网关上报（机器凭据）")
public class PersonnelPositionIngestController {

    private final PersonnelPositionService service;

    public PersonnelPositionIngestController(PersonnelPositionService service) {
        this.service = service;
    }

    /** 单个标签的位置上报体；x/y 为百分比坐标，battery 与 accuracy 可缺省。 */
    public record PositionRequest(String id, Double x, Double y, Integer battery, Double accuracy) {}

    public record IngestRequest(String source, List<PositionRequest> positions) {}

    @PostMapping("/person-position")
    @Operation(summary = "上报人员实时位置，未知人员编码忽略")
    public Map<String, Object> personPosition(@RequestBody(required = false) IngestRequest body) {
        List<PositionRequest> positions = body == null || body.positions() == null
                ? List.of() : body.positions();
        int applied = 0;
        List<String> ignored = new ArrayList<>();
        List<String> appliedIds = new ArrayList<>();
        for (PositionRequest p : positions) {
            if (p == null || p.id() == null || p.id().isBlank()) {
                ignored.add("<empty>");
                continue;
            }
            boolean ok = service.apply(p.id(), p.x(), p.y(), p.battery(), p.accuracy());
            if (ok) {
                applied++;
                appliedIds.add(p.id().trim());
            } else {
                ignored.add(p.id());
            }
        }
        service.announceMoved(appliedIds);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applied", applied);
        result.put("ignored", ignored);
        result.put("source", body == null || body.source() == null ? "unknown" : body.source());
        return result;
    }
}
