package com.bproject.safety.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * IAM 会话内省 TTL 缓存（待办 #7）：TTL 内复用结果（不外呼）、过期后重新外呼、
 * logout 主动失效、TTL=0 保持逐请求外呼的旧语义。撤销判定（ScsSessionRegistry）先于缓存，不受影响。
 */
class ScsIamTicketValidatorCacheTest {

    private HttpServer server;
    private final AtomicInteger introspectHits = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean sessionActive =
            new java.util.concurrent.atomic.AtomicBoolean(true);
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void startStub() throws Exception {
        introspectHits.set(0);
        sessionActive.set(true);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/sso/session/introspect", exchange -> {
            introspectHits.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes());
            String subject = extractField(body, "subject");
            String sid = extractField(body, "sessionId");
            byte[] resp = mapper.writeValueAsBytes(Map.of(
                    "code", 0,
                    "data", Map.of("active", sessionActive.get(), "subject", subject, "sessionId", sid,
                            "audience", "scs", "roles", java.util.List.of("ADMIN"), "permissions", java.util.List.of())));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.createContext("/auth/sso/session/logout", exchange -> {
            byte[] resp = mapper.writeValueAsBytes(Map.of("code", 0));
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        if (server != null) server.stop(0);
    }

    private static String extractField(String body, String field) {
        // 简易提取，避免测试内再依赖 Jackson 反序列化请求体
        var m = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(body);
        return m.find() ? m.group(1) : "";
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void ttlReusesIntrospectionWithinWindowThenRequeriesAfterExpiry() throws Exception {
        ScsIamTicketValidator validator = new ScsIamTicketValidator(
                baseUrl(), "token", "scs", 2000, mapper, 120);
        Map<String, Object> first = validator.current("leiwen", "sid-1");
        Map<String, Object> second = validator.current("leiwen", "sid-1");
        assertEquals(1, introspectHits.get()); // TTL 内复用
        assertEquals(first, second);

        Thread.sleep(200); // 过期
        validator.current("leiwen", "sid-1");
        assertEquals(2, introspectHits.get());

        // 另一会话独立缓存
        validator.current("leiwen", "sid-2");
        assertEquals(3, introspectHits.get());
    }

    @Test
    void logoutEvictsCacheSoNextReadReintrospects() throws Exception {
        ScsIamTicketValidator validator = new ScsIamTicketValidator(
                baseUrl(), "token", "scs", 2000, mapper, 60_000);
        validator.current("leiwen", "sid-1");
        validator.current("leiwen", "sid-1");
        assertEquals(1, introspectHits.get());

        validator.logout("leiwen", "sid-1");
        validator.current("leiwen", "sid-1");
        assertEquals(2, introspectHits.get()); // 注销后缓存已失效
    }

    @Test
    void zeroTtlKeepsLegacyPerCallIntrospection() {
        ScsIamTicketValidator validator = new ScsIamTicketValidator(
                baseUrl(), "token", "scs", 2000, mapper, 0);
        validator.current("leiwen", "sid-1");
        validator.current("leiwen", "sid-1");
        assertEquals(2, introspectHits.get()); // TTL=0 每次外呼（旧行为）
    }

    @Test
    void inactiveSessionThrowsAndIsNotCached() {
        ScsIamTicketValidator validator = new ScsIamTicketValidator(
                baseUrl(), "token", "scs", 2000, mapper, 60_000);
        sessionActive.set(false);
        assertThrows(ScsIamTicketValidator.InvalidTicketException.class,
                () -> validator.current("leiwen", "sid-x"));
        // 失败结果不得进入缓存
        assertEquals(0, currentCacheSize());
        // 恢复后再次调用走真实外呼并成功
        sessionActive.set(true);
        var result = validator.current("leiwen", "sid-x");
        assertEquals("leiwen", result.get("sub"));
        assertEquals(2, introspectHits.get()); // 失败调用 1 次 + 恢复后的成功外呼 1 次（失败未入缓存所以必然重试）
    }

    private static int currentCacheSize() {
        try {
            var field = ScsIamTicketValidator.class.getDeclaredField("SESSION_CACHE");
            field.setAccessible(true);
            return ((ConcurrentHashMap<?, ?>) field.get(null)).size();
        } catch (Exception e) {
            return -1;
        }
    }
}
