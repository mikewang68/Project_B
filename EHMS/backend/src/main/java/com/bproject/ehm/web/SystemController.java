package com.bproject.ehm.web;

import com.bproject.ehm.config.UnifiedConfigService;
import com.mongodb.client.MongoClient;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class SystemController {
    private final MongoClient mongoClient;
    private final UnifiedConfigService unifiedConfig;
    private final String databaseName;

    public SystemController(MongoClient mongoClient,
                            UnifiedConfigService unifiedConfig,
                            @Value("${spring.data.mongodb.database:ehm}") String databaseName) {
        this.mongoClient = mongoClient;
        this.unifiedConfig = unifiedConfig;
        this.databaseName = databaseName;
    }

    @GetMapping("/health/live")
    public Map<String, Object> live() {
        return Map.of("status", "UP", "service", "ehm-demo", "time", Instant.now());
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            mongoClient.getDatabase(databaseName).runCommand(new Document("ping", 1));
            result.put("status", "UP");
            result.put("mongo", "UP");
            result.put("time", Instant.now());
            return ResponseEntity.ok(result);
        } catch (Exception exception) {
            result.put("status", "DOWN");
            result.put("mongo", "DOWN");
            result.put("message", exception.getMessage());
            result.put("time", Instant.now());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
        }
    }

    @GetMapping("/api/ehm/v1/system/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", "ehm-demo");
        result.put("status", "UP");
        result.put("database", "MongoDB");
        result.put("config", unifiedConfig.publicSummary());
        result.put("time", Instant.now());
        return result;
    }
}
