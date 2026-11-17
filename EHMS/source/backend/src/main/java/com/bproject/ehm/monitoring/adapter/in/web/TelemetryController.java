package com.bproject.ehm.monitoring.adapter.in.web;

import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/ehm/v1/telemetry")
public class TelemetryController {
    private final TelemetrySeriesPort telemetry;
    private final com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore committed;

    @org.springframework.beans.factory.annotation.Autowired
    public TelemetryController(TelemetrySeriesPort telemetry,
            org.springframework.beans.factory.ObjectProvider<com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore> store) {
        this.telemetry = telemetry;
        this.committed = store.getIfAvailable();
    }

    @GetMapping("/points/{pointCode}/samples")
    public List<TelemetrySeriesPort.TelemetrySample> history(
            @PathVariable String pointCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "500") int limit
    ) {
        Instant end=to==null?Instant.now():to,start=from==null?end.minusSeconds(86400):from;
        if(!start.isBefore(end) || java.time.Duration.between(start,end).compareTo(java.time.Duration.ofDays(90))>0)
            throw new com.bproject.ehm.shared.error.ValidationException("请选择有效起止时间，单次历史查询不超过90天");
        if(limit<1||limit>2000)throw new com.bproject.ehm.shared.error.ValidationException("历史查询条数须为1至2000");
        if(pointCode.startsWith("MQ-SIM-")){
            if(committed==null)throw new IllegalStateException("PUBLIC_COMMITTED_TELEMETRY_STORE_UNAVAILABLE");
            return committed.recent("committed-observation","pointCode",pointCode,"sourceTimestamp",start,end,limit,com.fasterxml.jackson.databind.JsonNode.class)
                .stream().map(raw->{var point=raw.path("rawPoint");var value=point.path("value");
                    String id=raw.path("eventId").asText(),original=raw.path("originalPointCode").asText();
                    return new TelemetrySeriesPort.TelemetrySample(pointCode,raw.path("assetCode").asText(),original,
                        point.path("unit").isTextual()?point.path("unit").asText():null,
                        value.isNumber()&&Double.isFinite(value.doubleValue())?value.doubleValue():null,
                        point.path("quality").isTextual()?point.path("quality").asText():"UNKNOWN",
                        time(raw.path("sourceTimestamp")),time(raw.path("receivedAt")),
                        id,raw.path("simulated").isBoolean()?raw.path("simulated").booleanValue():null,observationKey(id,original));
                }).sorted(java.util.Comparator.comparing(TelemetrySeriesPort.TelemetrySample::sourceTimestamp)).toList();
        }
        return telemetry.history(pointCode, start, end, limit);
    }
    @GetMapping("/points/{pointCode}/observations/{reference}")
    public com.fasterxml.jackson.databind.JsonNode observation(@PathVariable String pointCode,@PathVariable String reference){
        if(committed==null)throw new com.bproject.ehm.shared.error.DependencyUnavailableException("公共观测库未配置");
        if(!reference.matches("[a-f0-9]{64}"))throw new com.bproject.ehm.shared.error.ValidationException("来源定位格式无效");
        var raw=committed.find("committed-observation",reference,com.fasterxml.jackson.databind.JsonNode.class)
            .orElseThrow(()->new com.bproject.ehm.shared.error.ResourceNotFoundException("未找到当前授权区域内的原始观测"));
        if(!pointCode.equals(raw.path("pointCode").asText()))throw new com.bproject.ehm.shared.error.ResourceNotFoundException("原始观测不属于所选测点");
        return raw;
    }
    static String observationKey(String id,String original){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest((id+"\n"+original).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static Instant time(com.fasterxml.jackson.databind.JsonNode value){
        if(value.isNumber()){var seconds=value.decimalValue();long whole=seconds.longValue();return Instant.ofEpochSecond(whole,seconds.subtract(java.math.BigDecimal.valueOf(whole)).movePointRight(9).longValue());}
        return Instant.parse(value.asText());
    }
}
