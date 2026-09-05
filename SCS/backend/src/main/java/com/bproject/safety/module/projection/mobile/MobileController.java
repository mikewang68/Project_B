package com.bproject.safety.module.projection.mobile;

import com.bproject.safety.module.alert.dto.AlertRequests.StartRequest;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.module.projection.mobile.MobileDtos.MobileHome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 移动端告警处置薄接口：内部只读写同一个 Alert（无独立 Repository）。
 * 接单/到场只推进 mobileStage，主状态机保持“待处理”；开始处理仍走 /alerts/{id}/start。
 */
@RestController
@RequestMapping("/api/v1/mobile")
@Tag(name = "移动端投影", description = "现场作业首页与接单/到场过渡动作（Alert 投影）")
public class MobileController {

    private final MobileService mobileService;
    private final AlertService alertService;

    public MobileController(MobileService mobileService, AlertService alertService) {
        this.mobileService = mobileService;
        this.alertService = alertService;
    }

    @GetMapping("/home")
    @Operation(summary = "移动端首页统计与待接单/处置中列表（Alert 投影）")
    public MobileHome home() {
        return mobileService.home();
    }

    @PostMapping("/incidents/{id}/accept")
    @Operation(summary = "移动端接单：mobileStage → ACCEPTED，主状态保持待处理")
    public DemoAlert accept(@PathVariable String id,
                            @RequestBody(required = false) StartRequest body,
                            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return alertService.mobileAccept(id, body, idemKey);
    }

    @PostMapping("/incidents/{id}/arrive")
    @Operation(summary = "移动端确认到场：mobileStage ACCEPTED → ARRIVED")
    public DemoAlert arrive(@PathVariable String id,
                            @RequestBody(required = false) StartRequest body,
                            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return alertService.mobileArrive(id, body, idemKey);
    }
}
