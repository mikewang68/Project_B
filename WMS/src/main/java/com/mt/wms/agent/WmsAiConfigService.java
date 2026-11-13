package com.mt.wms.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * WMS 仓储智能体模型配置：URL / 模型 / 密钥存入业务库，调用时动态读取。
 *
 * <p>密钥只存 AES-256-GCM 密文，接口只返回 last4；数据库无配置行时回退
 * {@code wms.agent.*} 环境变量。首次保存时若未填 Key 且环境变量已有 Key，
 * 会把环境变量 Key 加密迁移到数据库，避免切换后智能体中断。
 */
@Service
public class WmsAiConfigService {

    public static final String SYSTEM_CODE = "WMS";
    private static final String CONFIG_ID = "AI-CFG-WMS";
    private static final String KEY_INFO = "wms-ai-provider-config-v1";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final JdbcTemplate db;
    private final String secret;
    private final boolean fallbackEnabled;
    private final String fallbackBaseUrl;
    private final String fallbackApiKey;
    private final String fallbackModel;
    private final int fallbackTimeoutSeconds;
    private final SecureRandom random = new SecureRandom();
    private volatile Snapshot cached;

    public WmsAiConfigService(
            JdbcTemplate db,
            @Value("${WMS_AI_CONFIG_SECRET:}") String secret,
            @Value("${wms.agent.enabled:true}") boolean fallbackEnabled,
            @Value("${wms.agent.base-url:http://100.65.200.125:18047/v1}") String fallbackBaseUrl,
            @Value("${wms.agent.api-key:}") String fallbackApiKey,
            @Value("${wms.agent.model:Qwen3.8-27B}") String fallbackModel,
            @Value("${wms.agent.timeout-seconds:30}") int fallbackTimeoutSeconds) {
        this.db = db;
        this.secret = secret == null ? "" : secret.trim();
        this.fallbackEnabled = fallbackEnabled;
        this.fallbackBaseUrl = fallbackBaseUrl == null ? "" : fallbackBaseUrl.trim();
        this.fallbackApiKey = fallbackApiKey == null ? "" : fallbackApiKey.trim();
        this.fallbackModel = fallbackModel == null ? "" : fallbackModel.trim();
        this.fallbackTimeoutSeconds = Math.max(5, Math.min(fallbackTimeoutSeconds, 60));
    }

    public record RuntimeConfig(boolean enabled, String baseUrl, String model, String apiKey,
                                int timeoutSeconds, String provider, String source) {
    }

    public record PublicConfig(boolean enabled, String baseUrl, String model,
                               boolean apiKeyConfigured, String apiKeyLast4,
                               int timeoutSeconds, String provider, int version,
                               String updatedBy, String updatedAt, String source) {
    }

    public record UpdateRequest(String baseUrl, String model, String apiKey,
                                Boolean clearApiKey, Boolean enabled, Integer timeoutSeconds,
                                String provider, Integer expectedVersion, String reason) {
    }

    private record Row(String baseUrl, String model, String ciphertext, String last4,
                       boolean enabled, int timeoutSeconds, String provider, int version,
                       String updatedBy, String updatedAt) {
    }

    private record Snapshot(Row row, long at) {
    }

    public PublicConfig publicConfig() {
        Row row = readRow();
        if (row != null) {
            return new PublicConfig(row.enabled(), row.baseUrl(), row.model(),
                    row.ciphertext() != null && !row.ciphertext().isBlank(), row.last4(),
                    row.timeoutSeconds(), row.provider(), row.version(), row.updatedBy(),
                    row.updatedAt(), "DB");
        }
        RuntimeConfig env = envRuntime();
        return new PublicConfig(env.enabled(), env.baseUrl(), env.model(),
                fallbackApiKey.length() >= 0 && !fallbackApiKey.isBlank(), null,
                env.timeoutSeconds(), env.provider(), 0, null, null, "ENV");
    }

    public RuntimeConfig runtime() {
        Row row = cachedRow();
        if (row == null) {
            return envRuntime();
        }
        String apiKey = fallbackApiKey;
        boolean decryptFailed = false;
        if (row.ciphertext() != null && !row.ciphertext().isBlank()) {
            try {
                apiKey = decrypt(row.ciphertext());
            } catch (Exception ex) {
                decryptFailed = true;
                apiKey = "";
            }
        }
        return new RuntimeConfig(row.enabled() && !decryptFailed,
                blank(row.baseUrl(), fallbackBaseUrl), blank(row.model(), fallbackModel),
                apiKey, row.timeoutSeconds(), blank(row.provider(), "qwen"), "DB");
    }

    public PublicConfig update(UpdateRequest request, String actor) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少配置内容");
        }
        String baseUrl = validateUrl(request.baseUrl());
        String model = validateModel(request.model());
        int timeoutSeconds = request.timeoutSeconds() == null ? DEFAULT_TIMEOUT_SECONDS : request.timeoutSeconds();
        if (timeoutSeconds < 5 || timeoutSeconds > 60) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "超时时间必须在 5-60 秒之间");
        }
        String provider = trimTo(request.provider(), 100);
        String who = trimTo(actor, 128);
        Row existing = readRow();
        if (existing == null) {
            String initialKey = request.apiKey() == null || request.apiKey().isBlank()
                    ? fallbackApiKey : request.apiKey().trim();
            String cipher = initialKey.isBlank() ? null : encrypt(initialKey);
            db.update("INSERT INTO public.ai_provider_config (id, system_code, base_url, model, "
                            + "api_key_ciphertext, api_key_last4, enabled, timeout_seconds, provider, "
                            + "version, updated_by, created_at, updated_at, valid) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)",
                    CONFIG_ID, SYSTEM_CODE, baseUrl, model, cipher, last4(initialKey),
                    bool(request.enabled()), timeoutSeconds, provider, who);
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
            int changed = db.update("UPDATE public.ai_provider_config SET base_url=?, model=?, "
                            + "api_key_ciphertext=?, api_key_last4=?, enabled=?, timeout_seconds=?, "
                            + "provider=?, version=version+1, updated_by=?, updated_at=CURRENT_TIMESTAMP, valid=1 "
                            + "WHERE system_code=? AND version=?",
                    baseUrl, model, cipher, last4, bool(request.enabled()), timeoutSeconds,
                    provider, who, SYSTEM_CODE, expected);
            if (changed != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "配置已被他人更新，请刷新后重试");
            }
        }
        cached = null;
        return publicConfig();
    }

    private RuntimeConfig envRuntime() {
        return new RuntimeConfig(fallbackEnabled, fallbackBaseUrl, fallbackModel, fallbackApiKey,
                fallbackTimeoutSeconds, "qwen", "ENV");
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
                            + "timeout_seconds, provider, version, updated_by, updated_at "
                            + "FROM public.ai_provider_config WHERE system_code=? AND valid=1 LIMIT 1", SYSTEM_CODE);
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> m = rows.get(0);
            return new Row(text(m.get("base_url")), text(m.get("model")),
                    text(m.get("api_key_ciphertext")), text(m.get("api_key_last4")),
                    number(m.get("enabled")) != 0, number(m.get("timeout_seconds")),
                    text(m.get("provider")), number(m.get("version")),
                    text(m.get("updated_by")), text(m.get("updated_at")));
        } catch (DataAccessException ex) {
            if (isMissingTable(ex)) {
                return null;
            }
            throw ex;
        }
    }

    private static boolean isMissingTable(DataAccessException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sql && "42P01".equals(sql.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private String encrypt(String plaintext) {
        if (secret.length() < 16) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 配置加密密钥未配置（WMS_AI_CONFIG_SECRET），暂不能保存密钥");
        }
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] data = Arrays.copyOfRange(encrypted, 0, encrypted.length - 16);
            byte[] tag = Arrays.copyOfRange(encrypted, encrypted.length - 16, encrypted.length);
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
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= 4 ? "****" : trimmed.substring(trimmed.length() - 4);
    }
}
