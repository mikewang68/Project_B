package com.bproject.safety.module.publicdata.web;

import com.bproject.safety.module.publicdata.PublicDataProperties;
import com.bproject.safety.module.publicdata.repository.PublicDeviceBindings;
import com.bproject.safety.module.publicdata.repository.PublicInboxRepository;
import com.bproject.safety.module.publicdata.service.PublicBackfillInvoker;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 公共数据接收链路状态（运维观测；权限 safety:module:ops:view）。 */
@RestController
@RequestMapping("/api/v1/public-data")
@ConditionalOnProperty(prefix = "app.public-data", name = "enabled", havingValue = "true")
public class PublicDataStatusController {

    private final PublicDataProperties properties;
    private final PublicInboxRepository inbox;
    private final PublicDeviceBindings bindings;
    private final PublicBackfillInvoker backfillInvoker;

    public PublicDataStatusController(PublicDataProperties properties, PublicInboxRepository inbox,
                                      PublicDeviceBindings bindings,
                                      @Autowired(required = false) PublicBackfillInvoker backfillInvoker) {
        this.properties = properties;
        this.inbox = inbox;
        this.bindings = bindings;
        this.backfillInvoker = backfillInvoker;
    }

    @GetMapping("/status")
    @Operation(summary = "公共数据接收链路状态（收件箱计数与设备绑定覆盖）")
    public Map<String, Object> status(@RequestParam(required = false) String action) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.enabled());
        out.put("nameserver", properties.nameserver());
        out.put("group", properties.group());
        out.put("topic", properties.topic());
        out.put("backfillEnabled", properties.backfillEnabled());
        out.put("cursor", inbox.backfillCursor());
        out.put("received", inbox.countByStatus("RECEIVED"));
        out.put("processed", inbox.countByStatus("PROCESSED"));
        out.put("failed", inbox.countByStatus("FAILED"));
        List<Map<String, Object>> bindingRows = bindings.list().stream()
                .map(b -> Map.<String, Object>of(
                        "deviceId", b.deviceId(),
                        "templateId", b.templateId(),
                        "domain", b.domain(),
                        "objectCode", b.objectCode(),
                        "enabled", b.enabled()))
                .toList();
        out.put("bindings", bindingRows);
        // 手动补读一页（写操作语义经权限层校验；参数 action=backfill）
        if ("backfill".equals(action) && backfillInvoker != null) {
            try {
                out.put("backfillInserted", backfillInvoker.backfillNow());
            } catch (Exception e) {
                out.put("backfillError", String.valueOf(e.getMessage()));
            }
        }
        return out;
    }
}
