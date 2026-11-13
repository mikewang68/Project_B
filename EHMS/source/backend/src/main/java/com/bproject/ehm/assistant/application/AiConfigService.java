package com.bproject.ehm.assistant.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * EHMS AI 服务配置：URL / 模型 / 密钥存入业务库，调用时动态读取。
 *
 * <p>密钥只以 AES-256-GCM 密文落库，接口只返回 last4；数据库没有配置行时回退到
 * 部署环境变量，避免升级或迁移尚未执行时中断现有助手。写操作要求
 * {@code EHM_AI_CONFIG_SECRET} 已配置，且只允许管理员（由 BusinessPermissionFilter 强制）。
 */
@Service
public class AiConfigService {

    public static final String SYSTEM_CODE = "EHMS";
    private static final String CONFIG_ID = "AI-CFG-EHMS";
    private static final String KEY_INFO = "ehm-ai-provider-config-v1";
    private static final int DEFAULT_TIMEOUT_MS = 45_000;
    private static final int DEFAULT_MAX_TOKENS = 1_800;

    private final JdbcTemplate db;
    private final String schema;
    private final String secret;
    private final boolean fallbackEnabled;
    private final String fallbackBaseUrl;
    private final String fallbackApiKey;
    private final String fallbackApiKeyFile;
    private final String fallbackModel;
    private final String fallbackPolicy;
    private final String fallbackProvider;
    private final long fallbackTimeoutSeconds;
    private final SecureRandom random = new SecureRandom();
    private volatile Snapshot cached;

    public AiConfigService(
            JdbcTemplate db,
            @Value("${ehm.persistence.schema:ehm}") String schema,
            @Value("${EHM_AI_CONFIG_SECRET:}") String secret,
            @Value("${ehm.ai.enabled:false}") boolean fallbackEnabled,
            @Value("${ehm.ai.base-url:https://api.deepseek.com}") String fallbackBaseUrl,
            @Value("${ehm.ai.api-key:}") String fallbackApiKey,
            @Value("${ehm.ai.api-key-file:}") String fallbackApiKeyFile,
            @Value("${ehm.ai.model:deepseek-flash}") String fallbackModel,
            @Value("${ehm.ai.data-policy:disabled}") String fallbackPolicy,
            @Value("${ehm.ai.provider:}") String fallbackProvider,
            @Value("${ehm.ai.timeout-seconds:45}") long fallbackTimeoutSeconds) {
        if (schema == null || !schema.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("invalid ehm.persistence.schema");
        }
        this.db = db;
        this.schema = schema;
        this.secret = secret == null ? "" : secret.trim();
        this.fallbackEnabled = fallbackEnabled;
        this.fallbackBaseUrl = fallbackBaseUrl == null ? "" : fallbackBaseUrl.trim();
        this.fallbackApiKey = fallbackApiKey == null ? "" : fallbackApiKey.trim();
        this.fallbackApiKeyFile = fallbackApiKeyFile == null ? "" : fallbackApiKeyFile.trim();
        this.fallbackModel = fallbackModel == null ? "" : fallbackModel.trim();
        this.fallbackPolicy = fallbackPolicy == null ? "disabled" : fallbackPolicy.trim();
        this.fallbackProvider = fallbackProvider == null ? "" : fallbackProvider.trim();
        this.fallbackTimeoutSeconds = Math.max(5, Math.min(fallbackTimeoutSeconds, 60));
    }

    public record RuntimeConfig(boolean enabled, String baseUrl, String model, String apiKey,
                                int timeoutMs, int maxTokens, String dataPolicy,
                                String provider, String source) {
    }

    public record PublicConfig(boolean enabled, String baseUrl, String model,
                               boolean apiKeyConfigured, String apiKeyLast4,
                               int timeoutMs, int maxTokens, String dataPolicy,
                               String provider, int version, String updatedBy,
                               String updatedAt, String source) {
    }

    public record UpdateRequest(String baseUrl, String model, String apiKey,
                                Boolean clearApiKey, Boolean enabled, Integer timeoutMs,
                                Integer maxTokens, String provider, String dataPolicy,
                                Integer expectedVersion, String reason) {
    }

    private record Row(String baseUrl, String model, String ciphertext, String last4,
                       boolean enabled, int timeoutMs, int maxTokens, String provider,
                       String dataPolicy, int version, String updatedBy, String updatedAt) {
    }

    private record Snapshot(Row row, long at) {
    }

    /** 页面读取：数据库存在配置行时以数据库为准；否则显示环境变量兜底值。 */
    public PublicConfig publicConfig() {
        Row row = readRow();
        if (row != null) {
            return new PublicConfig(row.enabled(), row.baseUrl(), row.model(),
                    row.ciphertext() != null && !row.ciphertext().isBlank(), row.last4(),
                    row.timeoutMs(), row.maxTokens(), row.dataPolicy(), row.provider(),
                    row.version(), row.updatedBy(), row.updatedAt(), "DB");
        }
        RuntimeConfig env = envRuntime();
        return new PublicConfig(env.enabled(), env.baseUrl(), env.model(),
                env.apiKey() != null && !env.apiKey().isBlank(), null,
                env.timeoutMs(), env.maxTokens(), env.dataPolicy(), env.provider(),
                0, null, null, "ENV");
    }

    /** 运行时读取：给 /assistant/status、/assistant/chat 与 /assistant/test 使用。 */
    public RuntimeConfig runtime() {
        Row row = cachedRow();
        if (row == null) {
            return envRuntime();
        }
        String apiKey = "";
        boolean decryptFailed = false;
        if (row.enabled() && row.ciphertext() != null && !row.ciphertext().isBlank()) {
            try {
                apiKey = decrypt(row.ciphertext());
            } catch (Exception ex) {
                decryptFailed = true;
            }
        }
        return new RuntimeConfig(row.enabled() && !decryptFailed,
                blank(row.baseUrl(), fallbackBaseUrl), blank(row.model(), fallbackModel),
                apiKey, row.timeoutMs(), row.maxTokens(), row.dataPolicy(),
                blank(row.provider(), fallbackProvider), "DB");
    }

    public PublicConfig update(UpdateRequest request, String actor) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少配置内容");
        }
        String baseUrl = validateUrl(request.baseUrl());
        String model = validateModel(request.model());
        int timeoutMs = request.timeoutMs() == null ? DEFAULT_TIMEOUT_MS : request.timeoutMs();
        if (timeoutMs < 1_000 || timeoutMs > 120_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "超时时间必须在 1000-120000 毫秒之间");
        }
        int maxTokens = request.maxTokens() == null ? DEFAULT_MAX_TOKENS : request.maxTokens();
        if (maxTokens < 1 || maxTokens > 32_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最大 tokens 必须在 1-32000 之间");
        }
        String policy = request.dataPolicy() == null ? "anonymized" : request.dataPolicy().trim();
        if (!policy.equals("anonymized") && !policy.equals("disabled")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据外发策略只能是 anonymized 或 disabled");
        }
        String provider = trimTo(request.provider(), 100);
        String who = trimTo(actor, 128);
        Row existing = readRow();
        if (existing == null) {
            if (request.apiKey() == null || request.apiKey().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "首次配置必须填写 API Key");
            }
            String cipher = encrypt(request.apiKey().trim());
            db.update("INSERT INTO " + table() + " (id, system_code, base_url, model, "
                            + "api_key_ciphertext, api_key_last4, enabled, timeout_ms, max_tokens, "
                            + "provider, data_policy, version, updated_by, created_at, updated_at, valid) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)",
                    CONFIG_ID, SYSTEM_CODE, baseUrl, model, cipher, last4(request.apiKey().trim()),
                    bool(request.enabled()), timeoutMs, maxTokens, provider, policy, who);
        } else {
            int expected = request.expectedVersion() == null ? existing.version() : request.expectedVersion();
            if (expected != existing.version()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "配置已被他人更新，请刷新后重试");
            }
            String cipher = existing.ciphertext();
            String last4 = existing.last4();
            if (Boolean.TRUE.equals(request.clearApiKey())) {
                cipher = null;
                last4 = null;
            } else if (request.apiKey() != null && !request.apiKey().isBlank()) {
                cipher = encrypt(request.apiKey().trim());
                last4 = last4(request.apiKey().trim());
            }
            int changed = db.update("UPDATE " + table() + " SET base_url=?, model=?, "
                            + "api_key_ciphertext=?, api_key_last4=?, enabled=?, timeout_ms=?, "
                            + "max_tokens=?, provider=?, data_policy=?, version=version+1, "
                            + "updated_by=?, updated_at=CURRENT_TIMESTAMP, valid=1 "
                            + "WHERE system_code=? AND version=?",
                    baseUrl, model, cipher, last4, bool(request.enabled()), timeoutMs,
                    maxTokens, provider, policy, who, SYSTEM_CODE, expected);
            if (changed != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "配置已被他人更新，请刷新后重试");
            }
        }
        cached = null;
        return publicConfig();
    }

    private RuntimeConfig envRuntime() {
        String key = fallbackApiKey;
        if (!fallbackApiKeyFile.isBlank()) {
            try {
                key = Files.readString(Path.of(fallbackApiKeyFile)).trim();
            } catch (Exception ignored) {
                key = "";
            }
        }
        String provider = fallbackProvider.isBlank()
                ? (fallbackBaseUrl.startsWith("https://api.deepseek.com") ? "DeepSeek" : "实验室大模型")
                : fallbackProvider;
        return new RuntimeConfig(fallbackEnabled, fallbackBaseUrl, fallbackModel, key,
                (int) Math.max(1_000, fallbackTimeoutSeconds * 1_000),
                DEFAULT_MAX_TOKENS, fallbackPolicy, provider, "ENV");
    }

    private Row cachedRow() {
        Snapshot snapshot = cached;
        long now = Instant.now().toEpochMilli();
        if (snapshot != null && now - snapshot.at() < 30_000) {
            return snapshot.row();
        }
        Row row = readRow();
        cached = new Snapshot(row, now);
        return row;
    }

    private Row readRow() {
        try {
            List<Map<String, Object>> rows = db.queryForList(
                    "SELECT base_url, model, api_key_ciphertext, api_key_last4, enabled, "
                            + "timeout_ms, max_tokens, provider, data_policy, version, "
                            + "updated_by, updated_at FROM " + table()
                            + " WHERE system_code=? AND valid=1 LIMIT 1", SYSTEM_CODE);
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> m = rows.get(0);
            return new Row(text(m.get("base_url")), text(m.get("model")),
                    text(m.get("api_key_ciphertext")), text(m.get("api_key_last4")),
                    number(m.get("enabled")) != 0, number(m.get("timeout_ms")),
                    number(m.get("max_tokens")), text(m.get("provider")),
                    text(m.get("data_policy")), number(m.get("version")),
                    text(m.get("updated_by")), text(m.get("updated_at")));
        } catch (DataAccessException ex) {
            // 迁移尚未执行时不要伪装成“已配置”：读取回退环境变量，写操作由表缺失明确报错。
            if (isMissingTable(ex)) {
                return null;
            }
            throw ex;
        }
    }

    private String table() {
        return schema + ".ai_provider_config";
    }

    private static boolean isMissingTable(DataAccessException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                if ("42P01".equals(state)) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    private String encrypt(String plaintext) {
        if (secret.length() < 16) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 配置加密密钥未配置（EHM_AI_CONFIG_SECRET），暂不能保存密钥");
        }
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] data = java.util.Arrays.copyOfRange(encrypted, 0, encrypted.length - 16);
            byte[] tag = java.util.Arrays.copyOfRange(encrypted, encrypted.length - 16, encrypted.length);
            return "v1:" + b64(iv) + ":" + b64(tag) + ":" + b64(data);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 配置密钥加密失败");
        }
    }

    private String decrypt(String ciphertext) {
        try {
            String[] parts = ciphertext.split(":");
            if (parts.length != 4 || !"v1".equals(parts[0])) {
                throw new IllegalArgumentException("unsupported ciphertext");
            }
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] tag = Base64.getDecoder().decode(parts[2]);
            byte[] data = Base64.getDecoder().decode(parts[3]);
            byte[] combined = new byte[data.length + tag.length];
            System.arraycopy(data, 0, combined, 0, data.length);
            System.arraycopy(tag, 0, combined, data.length, tag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(combined), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("AI 配置密钥解密失败");
        }
    }

    private SecretKeySpec key() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((KEY_INFO + ":" + secret).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    private static String b64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static String validateUrl(String value) {
        String url = value == null ? "" : value.trim();
        if (url.isEmpty() || url.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接口地址不能为空且最长 500 字符");
        }
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接口地址必须是 http/https URL");
        }
        return url;
    }

    private static String validateModel(String value) {
        String model = value == null ? "" : value.trim();
        if (model.isEmpty() || model.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型不能为空且最长 200 字符");
        }
        return model;
    }

    private static String trimTo(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int number(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return 0;
        }
    }

    private static int bool(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }

    private static String last4(String value) {
        return value.length() <= 4 ? "****" : value.substring(value.length() - 4);
    }
}
