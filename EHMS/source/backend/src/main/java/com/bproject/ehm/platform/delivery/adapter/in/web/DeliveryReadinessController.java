package com.bproject.ehm.platform.delivery.adapter.in.web;

import com.bproject.ehm.platform.health.ports.ReadinessProbe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class DeliveryReadinessController {
    private final ReadinessProbe readinessProbe;
    private final String persistenceAdapter;
    private final String telemetryAdapter;
    private final String releaseVersion;
    private final boolean aiEnabled;
    private final boolean jwtEnabled;
    private final boolean rbacEnabled;
    private final boolean tlsEnabled;

    public DeliveryReadinessController(ReadinessProbe readinessProbe,
                                       @Value("${ehm.persistence.adapter:MongoDB}") String persistenceAdapter,
                                       @Value("${ehm.telemetry.adapter:disabled}") String telemetryAdapter,
                                       @Value("${ehm.release.version:1.3.0-server}") String releaseVersion,
                                       @Value("${ehm.ai.enabled:false}") boolean aiEnabled,
                                       @Value("${ehm.security.jwt-enabled:false}") boolean jwtEnabled,
                                       @Value("${ehm.security.rbac-enabled:false}") boolean rbacEnabled,
                                       @Value("${ehm.security.tls-enabled:false}") boolean tlsEnabled) {
        this.readinessProbe = readinessProbe;
        this.persistenceAdapter = persistenceAdapter;
        this.telemetryAdapter = telemetryAdapter;
        this.releaseVersion = releaseVersion;
        this.aiEnabled = aiEnabled;
        this.jwtEnabled = jwtEnabled;
        this.rbacEnabled = rbacEnabled;
        this.tlsEnabled = tlsEnabled;
    }

    @GetMapping("/api/ehm/v1/system/capabilities")
    public List<Map<String, Object>> capabilities() {
        boolean mongoActive = "mongodb".equalsIgnoreCase(persistenceAdapter)
                || "mongo".equalsIgnoreCase(persistenceAdapter);
        boolean openGaussActive = "opengauss".equalsIgnoreCase(persistenceAdapter);
        boolean openGeminiActive = "opengemini".equalsIgnoreCase(telemetryAdapter);
        return List.of(
                capability("mongo", "MongoDB业务存储", mongoActive ? "ACTIVE" : "DISABLED",
                        mongoActive ? "当前业务存储适配器" : "服务器版未启用",
                        "设备、告警、工单、评估、备件和治理数据"),
                capability("opengauss", "openGauss业务库", openGaussActive ? "ACTIVE" : "RESERVED",
                        openGaussActive ? "业务CRUD持久化已启用" : "当前持久化适配器未选用",
                        "设备、告警、工单、评估、备件和治理数据"),
                capability("opengemini", "openGemini时序库", openGeminiActive ? "ACTIVE" : "RESERVED",
                        openGeminiActive ? "时序样本读写适配器已启用，现场实时采集待联调" : "时序适配器未启用",
                        "高频测点、波形和趋势数据"),
                capability("kvrocks", "Kvrocks缓存", "RESERVED", "缓存适配器待实现", "会话、热点状态、限流和幂等"),
                capability("rocketmq", "RocketMQ事件总线", "RESERVED", "事件适配器待实现", "告警、工单和跨系统事件"),
                capability("easegress", "Easegress统一网关", "RESERVED", "由六系统统一环境接入", "固定API前缀、限流和路由"),
                capability("ai", "外部AI推理", aiEnabled ? "ACTIVE" : "DISABLED", "默认使用可审计规则模式", "问答、知识检索和诊断辅助"),
                capability("jwt", "统一登录令牌", jwtEnabled ? "ACTIVE" : "BLOCKED", "等待统一身份体系", "登录认证和会话管理"),
                capability("rbac", "RBAC与数据权限", rbacEnabled ? "ACTIVE" : "BLOCKED", "当前仅展示权限设计", "API强制鉴权和设备范围控制"),
                capability("tls", "TLS传输加密", tlsEnabled ? "ACTIVE" : "BLOCKED", "等待证书和统一入口", "正式环境HTTPS")
        );
    }

    @GetMapping("/api/ehm/v1/system/delivery-readiness")
    public Map<String, Object> deliveryReadiness() {
        ReadinessProbe.ProbeResult probe = readinessProbe.check();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("edition", releaseVersion);
        result.put("demoDeliverable", probe.ready());
        result.put("productionReady", false);
        result.put("runtime", probe.ready() ? "UP" : "DOWN");
        result.put("completedScope", List.of("资产/BOM/测点", "时序样本与数据质量准入", "告警诊断工单复测", "健康评估与Demo预测", "维保点检缺陷", "备件库存", "FMECA/规则/知识库/SLA", "操作审计"));
        result.put("productionBlockers", List.of("现场PLC、网关、点表与采样口径未冻结", "统一身份认证、RBAC和数据权限尚未启用", "TLS证书及统一API网关尚未接入", "现场时序采集链路、消息队列和监控平台尚未全部联调", "AI与RUL模型仍需真实历史数据、标签和实机验证"));
        result.put("time", Instant.now());
        return result;
    }

    private Map<String, Object> capability(String id, String name, String status, String condition, String scope) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("name", name);
        item.put("status", status);
        item.put("condition", condition);
        item.put("scope", scope);
        return item;
    }
}
