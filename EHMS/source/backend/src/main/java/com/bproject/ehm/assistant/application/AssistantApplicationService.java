package com.bproject.ehm.assistant.application;

import com.bproject.ehm.alarm.application.AlarmQueryFacade;
import com.bproject.ehm.alarm.application.AlarmView;
import com.bproject.ehm.asset.application.AssetQueryFacade;
import com.bproject.ehm.asset.application.DeviceView;
import com.bproject.ehm.assistant.ports.AssistantModelPort;
import com.bproject.ehm.maintenance.application.WorkOrderQueryFacade;
import com.bproject.ehm.shared.error.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AssistantApplicationService {
    private static final Pattern DEVICE_CODE = Pattern.compile("\\b[A-Z]{2,5}-\\d{1,5}\\b");

    private final AssetQueryFacade assets;
    private final AlarmQueryFacade alarms;
    private final WorkOrderQueryFacade workOrders;
    private final AssistantModelPort model;
    private final Clock clock;

    @Autowired
    public AssistantApplicationService(AssetQueryFacade assets, AlarmQueryFacade alarms,
                                       WorkOrderQueryFacade workOrders, AssistantModelPort model) {
        this(assets, alarms, workOrders, model, Clock.systemUTC());
    }

    AssistantApplicationService(AssetQueryFacade assets, AlarmQueryFacade alarms,
                                WorkOrderQueryFacade workOrders, AssistantModelPort model, Clock clock) {
        this.assets = assets;
        this.alarms = alarms;
        this.workOrders = workOrders;
        this.model = model;
        this.clock = clock;
    }

    public AssistantResponse answer(String message) {
        String clean = message == null ? "" : message.trim();
        if (clean.isEmpty()) return new AssistantResponse("请输入需要分析的问题。", "validation", clock.instant());
        Optional<String> code = detectDeviceCode(clean);
        String context = buildContext(code);
        Optional<String> external = model.answer(clean, context);
        if (external.isPresent()) return new AssistantResponse(external.get(), "external-api", clock.instant());
        return new AssistantResponse(fallback(clean, code), "rule-demo", clock.instant());
    }

    private String buildContext(Optional<String> code) {
        if (code.isEmpty()) return "未指定设备；当前活动告警=" + alarms.metrics().open()
                + "；当前活动工单=" + workOrders.metrics().active() + "。";
        try {
            DeviceView device = assets.get(code.get());
            List<AlarmView> active = alarms.findOpenByDeviceCode(code.get(), 20);
            return "设备=" + device + "；该设备活动告警=" + active
                    + "；全场活动工单=" + workOrders.metrics().active() + "。";
        } catch (ResourceNotFoundException exception) {
            return "用户提供的设备编码未找到：" + code.get() + "。";
        }
    }

    private String fallback(String message, Optional<String> code) {
        if (containsAny(message, "RUL", "寿命", "预测")) {
            return "RUL预测当前未启用。现阶段可使用规则和趋势预警；启用前需要退化样本、故障/更换标签、模型版本和验证报告，不能给出伪精确剩余寿命。";
        }
        if (containsAny(message, "检查", "处置", "建议")) {
            return "建议依次核验传感器安装与校准、对齐载荷工况、复测关键测点、补充油样或现场照片，再由设备工程师确认候选原因并安排工单。系统不直接下发PLC控制指令。";
        }
        if (code.isPresent()) {
            try {
                DeviceView device = assets.get(code.get());
                long active = alarms.findOpenByDeviceCode(code.get(), 100).size();
                return device.code() + "（" + device.name() + "）当前健康分为"
                        + (device.health() == null ? "不可评估" : device.health()) + "，风险状态为“"
                        + device.risk() + "”，活动告警" + active + "项，主要事项为“" + device.alarm()
                        + "”。这是演示数据，候选原因需要现场复核。";
            } catch (ResourceNotFoundException exception) {
                return "未找到设备“" + code.get() + "”，请确认设备编码。";
            }
        }
        return "当前系统已接入设备、告警和工单数据。你可以询问GT-01等设备风险、现场检查项或RUL启用条件。未配置外部AI接口时，系统使用可审计的固定规则回答。";
    }

    private Optional<String> detectDeviceCode(String message) {
        Matcher matcher = DEVICE_CODE.matcher(message.toUpperCase(Locale.ROOT));
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    public record AssistantResponse(String answer, String mode, Instant answeredAt) {
    }
}
