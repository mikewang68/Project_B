package com.mt.wms.common.health;

import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/health")
public class HealthController {
    private final ObjectProvider<DataSource> dataSource;
    private final ObjectProvider<BuildProperties> buildProperties;

    public HealthController(ObjectProvider<DataSource> dataSource, ObjectProvider<BuildProperties> buildProperties) {
        this.dataSource = dataSource;
        this.buildProperties = buildProperties;
    }

    @GetMapping("/live")
    ApiResponse<Map<String, Object>> live(HttpServletRequest request) {
        return ApiResponse.ok(Map.of("status", "UP", "checkedAt", Instant.now()), requestId(request));
    }

    @GetMapping("/ready")
    ResponseEntity<ApiResponse<Map<String, Object>>> ready(HttpServletRequest request) {
        boolean databaseReady = databaseReady();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", databaseReady ? "UP" : "DOWN");
        data.put("database", databaseReady ? "UP" : "DOWN");
        data.put("checkedAt", Instant.now());
        return ResponseEntity.status(databaseReady ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.ok(data, requestId(request)));
    }

    @GetMapping("/version")
    ApiResponse<Map<String, String>> version(HttpServletRequest request) {
        BuildProperties build = buildProperties.getIfAvailable();
        return ApiResponse.ok(Map.of(
                "name", build == null ? "mt-wms" : build.getName(),
                "version", build == null ? "development" : build.getVersion()), requestId(request));
    }

    private boolean databaseReady() {
        DataSource candidate = dataSource.getIfAvailable();
        if (candidate == null) return false;
        try (Connection connection = candidate.getConnection()) {
            return connection.isValid(2);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String requestId(HttpServletRequest request) {
        return request.getAttribute(RequestIdFilter.ATTRIBUTE).toString();
    }
}

