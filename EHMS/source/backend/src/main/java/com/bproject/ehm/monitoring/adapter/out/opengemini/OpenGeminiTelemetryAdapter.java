package com.bproject.ehm.monitoring.adapter.out.opengemini;

import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort;
import com.bproject.ehm.shared.error.DependencyUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Profile("server")
public class OpenGeminiTelemetryAdapter implements TelemetrySeriesPort {
    private static final String MEASUREMENT = "ehm_point_value";

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String database;
    private final boolean writeEnabled;
    private final String baseUrl;

    public OpenGeminiTelemetryAdapter(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${ehm.telemetry.base-url}") String baseUrl,
            @Value("${ehm.telemetry.database:ehm_telemetry}") String database,
            @Value("${ehm.telemetry.username:}") String username,
            @Value("${ehm.telemetry.password:}") String password,
            @Value("${ehm.telemetry.connect-timeout:5s}") Duration connectTimeout,
            @Value("${ehm.telemetry.read-timeout:15s}") Duration readTimeout,
            @Value("${ehm.telemetry.write-enabled:true}") boolean writeEnabled
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        RestClient.Builder configured = builder.clone().baseUrl(trimSlash(baseUrl)).requestFactory(requestFactory);
        if (username != null && !username.isBlank()) {
            configured.defaultHeaders(headers -> headers.setBasicAuth(username, password == null ? "" : password));
        }
        this.client = configured.build();
        this.objectMapper = objectMapper;
        this.database = required(database, "openGemini数据库");
        this.writeEnabled = writeEnabled;
        this.baseUrl = trimSlash(baseUrl);
    }

    @Override
    public void write(TelemetrySample sample) {
        if (!writeEnabled) throw new DependencyUnavailableException("openGemini写入已关闭，样本未保存");
        try {
          client.post()
                .uri(URI.create(baseUrl + "/write?db=" + encode(database) + "&precision=ns"))
                .contentType(MediaType.TEXT_PLAIN)
                .body(lineProtocol(sample))
                .retrieve()
                .toBodilessEntity();
        } catch (org.springframework.web.client.RestClientException exception) {
            throw new DependencyUnavailableException("openGemini样本写入失败，请检查时序服务、数据库和写入权限", exception);
        }
    }

    @Override
    public List<TelemetrySample> history(String pointCode, Instant from, Instant to, int limit) {
        int bounded = Math.max(1, Math.min(limit, 2000));
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofHours(24)) : from;
        if (start.isAfter(end)) throw new IllegalArgumentException("开始时间不得晚于结束时间");
        String query = "SELECT value,quality,unit,metric,asset_code,source_timestamp,received_at FROM "
                + MEASUREMENT + " WHERE point_code='" + influxLiteral(pointCode)
                + "' AND time >= '" + start + "' AND time <= '" + end
                + "' ORDER BY time DESC LIMIT " + bounded;
        try {
            // URI overload preserves the single encoding; String overload encodes % a second time.
            String body = client.get().uri(queryUri(query)).retrieve().body(String.class);
            return decodeHistory(body, pointCode);
        } catch (org.springframework.web.client.RestClientException exception) {
            throw new DependencyUnavailableException("openGemini历史查询失败，请检查时序数据库、查询权限和测点入库状态", exception);
        }
    }

    URI queryUri(String query) {
        return URI.create(baseUrl + "/query?db=" + encode(database) + "&epoch=ms&q=" + encode(query));
    }

    @Override
    public void ping() {
        client.get().uri("/ping").retrieve().toBodilessEntity();
    }

    String lineProtocol(TelemetrySample sample) {
        Instant eventTime = sample.sourceTimestamp() == null ? Instant.now() : sample.sourceTimestamp();
        long timestampNs = Math.addExact(Math.multiplyExact(eventTime.getEpochSecond(), 1_000_000_000L),
                eventTime.getNano());
        return MEASUREMENT
                + ",point_code=" + influxTag(sample.pointCode())
                + ",asset_code=" + influxTag(sample.assetCode())
                + ",metric=" + influxTag(sample.metric())
                + " value=" + finite(sample.value())
                + ",quality=\"" + influxString(sample.quality()) + "\""
                + ",unit=\"" + influxString(sample.unit()) + "\""
                + ",source_timestamp=\"" + eventTime + "\""
                + ",received_at=\"" + (sample.receivedAt() == null ? Instant.now() : sample.receivedAt()) + "\" "
                + timestampNs;
    }

    List<TelemetrySample> decodeHistory(String body, String pointCode) {
        if (body == null || body.isBlank()) return List.of();
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.hasNonNull("error")) throw new DependencyUnavailableException("openGemini拒绝查询，请核查数据库与查询权限");
            List<TelemetrySample> values = new ArrayList<>();
            for (JsonNode result : root.path("results")) {
            if (result.hasNonNull("error")) throw new DependencyUnavailableException("openGemini返回查询错误，请核查数据库与测点表");
            for (JsonNode series : result.path("series")) {
            Map<String, Integer> columns = new HashMap<>();
            for (int i = 0; i < series.path("columns").size(); i++) {
                columns.put(series.path("columns").path(i).asText(), i);
            }
            for (JsonNode row : series.path("values")) {
                values.add(new TelemetrySample(
                        pointCode,
                        text(row, columns, "asset_code"),
                        text(row, columns, "metric"),
                        text(row, columns, "unit"),
                        number(row, columns, "value"),
                        text(row, columns, "quality"),
                        instant(text(row, columns, "source_timestamp")),
                        instant(text(row, columns, "received_at"))
                ));
            }
            }
            }
            return values;
        } catch (DependencyUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DependencyUnavailableException("openGemini历史结果格式异常", exception);
        }
    }

    private static String text(JsonNode row, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null || row.path(index).isNull() ? null : row.path(index).asText();
    }

    private static Double number(JsonNode row, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null || !row.path(index).isNumber() ? null : row.path(index).asDouble();
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static double finite(Double value) {
        if (value == null || !Double.isFinite(value)) throw new IllegalArgumentException("时序采样值必须是有限数字");
        return value;
    }

    private static String influxTag(String value) {
        return required(value, "时序标签").replace("\\", "\\\\")
                .replace(" ", "\\ ").replace(",", "\\,").replace("=", "\\=");
    }

    private static String influxString(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String influxLiteral(String value) {
        return required(value, "测点编码").replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trimSlash(String value) {
        String selected = required(value, "openGemini地址");
        return selected.endsWith("/") ? selected.substring(0, selected.length() - 1) : selected;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }
}
