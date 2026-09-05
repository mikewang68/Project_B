package com.bproject.ehm.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class UnifiedConfigService {
    private static final Logger log = LoggerFactory.getLogger(UnifiedConfigService.class);

    private final ObjectMapper objectMapper;
    private final Path configPath;
    private JsonNode config;

    public UnifiedConfigService(ObjectMapper objectMapper,
                                @Value("${ehm.config.path:../.config.json}") String configPath) {
        this.objectMapper = objectMapper;
        this.configPath = Path.of(configPath).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void load() {
        if (!Files.isRegularFile(configPath)) {
            log.warn("统一配置文件不存在，将使用Spring环境变量配置：{}", configPath);
            return;
        }
        try {
            config = objectMapper.readTree(configPath.toFile());
            log.info("已读取统一配置文件：{}", configPath);
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取统一配置文件：" + configPath, exception);
        }
    }

    public Map<String, Object> publicSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("loaded", config != null);
        result.put("path", configPath.toString());
        if (config != null) {
            result.put("configVersion", text("/configVersion"));
            result.put("application", text("/application/displayName"));
            result.put("environment", text("/application/environment"));
            result.put("mockDataEnabled", bool("/frontend/mockDataEnabled", true));
            result.put("backendConfigured", bool("/backend/enabled", false));
            result.put("demoPrimaryDatabase", text("/datasources/demoPrimary/type"));
            result.put("targetBusinessDatabase", text("/datasources/business/type"));
            result.put("targetTelemetryDatabase", text("/datasources/telemetry/type"));
        }
        return result;
    }

    private String text(String pointer) {
        JsonNode node = config.at(pointer);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private boolean bool(String pointer, boolean fallback) {
        JsonNode node = config.at(pointer);
        return node.isMissingNode() || node.isNull() ? fallback : node.asBoolean(fallback);
    }
}
