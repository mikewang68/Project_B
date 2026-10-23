package com.bproject.safety.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("G4 安全鉴权拦截器测试（验签 + 后门移除）")
class SecurityInterceptorTest {

    private static final String API_KEY = "offline-unit-only-ingestion-key-never-deploy";
    /** 测试专用验签密钥，仅存在于单元测试，长度 > 32 字节。 */
    private static final String JWT_SECRET = "scs-unit-test-hs256-secret-0123456789abcdef";
    private static final String AUDIENCE = "safety";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private SecurityInterceptor interceptor(boolean enabled, String jwtSecret) {
        return new SecurityInterceptor(
                enabled, API_KEY, "", jwtSecret, "iam", 60,
                "", "", AUDIENCE, 3000, objectMapper);
    }

    private SecurityInterceptor enabledInterceptor() {
        return interceptor(true, JWT_SECRET);
    }

    @Test
    @DisplayName("安全开关关闭时(enabled=false)：所有请求直接放行")
    void disabledPassesAll() throws Exception {
        SecurityInterceptor interceptor = interceptor(false, "");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/fences");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("安全开关开启时：OPTIONS 预检与白名单路径直接放行")
    void enabledOptionsAndWhitelistPass() throws Exception {
        SecurityInterceptor interceptor = enabledInterceptor();

        String[][] cases = {
                {"OPTIONS", "/api/v1/fences"},
                {"GET", "/actuator/health"},
                {"GET", "/health/live"},
                {"GET", "/health/ready"},
        };
        for (String[] c : cases) {
            MockHttpServletRequest req = new MockHttpServletRequest(c[0], c[1]);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(req, resp, new Object()))
                    .as("%s %s", c[0], c[1])
                    .isTrue();
        }
    }

    @Test
    @DisplayName("无凭据、错误 API Key、非 JWT 字符串一律 401")
    void unauthorizedRejected() throws Exception {
        SecurityInterceptor interceptor = enabledInterceptor();

        for (String path : new String[]{"/api/v1/auth/me", "/api/v1/state", "/api/v1/meta/dictionaries"}) {
            var protectedResponse = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(new MockHttpServletRequest("GET", path), protectedResponse, new Object())).isFalse();
            assertThat(protectedResponse.getStatus()).isEqualTo(401);
        }

        MockHttpServletResponse noCred = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(new MockHttpServletRequest("GET", "/api/v1/fences"), noCred, new Object())).isFalse();
        assertThat(noCred.getStatus()).isEqualTo(401);
        assertThat(noCred.getContentAsString()).contains("UNAUTHORIZED");

        MockHttpServletRequest badKey = new MockHttpServletRequest("POST", "/api/v1/ai-events/ingest");
        badKey.addHeader("X-API-Key", "invalid-key");
        MockHttpServletResponse badKeyResp = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(badKey, badKeyResp, new Object())).isFalse();
        assertThat(badKeyResp.getStatus()).isEqualTo(401);

        for (String raw : new String[]{"bad-token", "scs-token-xyz", "scs-readonly-token", "a.b.c"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/alerts");
            req.addHeader("Authorization", "Bearer " + raw);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(req, resp, new Object())).as(raw).isFalse();
            assertThat(resp.getStatus()).as(raw).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("G4-1: 静态 bearer-token 即使配置也不再放行（后门已移除）")
    void staticBearerTokenRejected() throws Exception {
        String legacy = "offline-unit-legacy-placeholder";
        SecurityInterceptor interceptor = new SecurityInterceptor(
                true, API_KEY, legacy, JWT_SECRET, "iam", 60,
                "", "", AUDIENCE, 3000, objectMapper);

        for (String raw : new String[]{legacy, legacy + "-readonly", "scs-readonly-token", "x-readonly-y"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/fences");
            req.addHeader("Authorization", "Bearer " + raw);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(req, resp, new Object())).as(raw).isFalse();
            assertThat(resp.getStatus()).as(raw).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("G4-2: alg=none 的伪造 JWT 被拒绝（旧实现会信任）")
    void algNoneRejected() throws Exception {
        SecurityInterceptor interceptor = enabledInterceptor();
        String forged = unsignedJwt("{\"sub\":\"attacker\",\"roles\":[\"ADMIN\"],\"aud\":\"safety\","
                + "\"exp\":" + (nowSeconds() + 3600) + "}", "none");

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/fences");
        req.addHeader("Authorization", "Bearer " + forged);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("G4-3: 篡改 payload 但沿用旧签名的 JWT 被拒绝")
    void tamperedSignatureRejected() throws Exception {
        SecurityInterceptor interceptor = enabledInterceptor();
        String valid = signedJwt("{\"sub\":\"auditor_01\",\"iss\":\"iam\",\"roles\":[\"ADMIN\"],\"aud\":\"safety\","
                + "\"exp\":" + (nowSeconds() + 3600) + "}", "HS256", JWT_SECRET);
        String[] parts = valid.split("\\.");
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"attacker\",\"roles\":[\"ADMIN\"],\"aud\":\"safety\",\"exp\":" + (nowSeconds() + 3600) + "}")
                        .getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/fences");
        req.addHeader("Authorization", "Bearer " + tampered);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("G4-4: 用其它密钥签名的 JWT 被拒绝")
    void wrongSecretRejected() throws Exception {
        SecurityInterceptor interceptor = enabledInterceptor();
        String otherSecret = "another-secret-that-is-long-enough-0123456789";
        String forged = signedJwt("{\"sub\":\"attacker\",\"iss\":\"iam\",\"roles\":[\"ADMIN\"],\"aud\":\"safety\",\"exp\":"
                + (nowSeconds() + 3600) + "}", "HS256", otherSecret);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/fences");
        req.addHeader("Authorization", "Bearer " + forged);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("JwtVerifier 拒绝短于 32 字节的密钥（fail-closed）")
    void shortSecretFailsConstruction() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> new JwtVerifier("short", "iam", 60, objectMapper)).getMessage()).contains("32");
    }

    @Test
    @DisplayName("JwtVerifier 拒绝 iss 不匹配的 JWT")
    void issuerMismatchRejected() {
        JwtVerifier verifier = new JwtVerifier(JWT_SECRET, "iam", 60, objectMapper);
        String wrongIss = signedJwt("{\"sub\":\"u\",\"iss\":\"evil\",\"aud\":\"safety\",\"exp\":"
                + (nowSeconds() + 3600) + "}", "HS256", JWT_SECRET);
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                JwtVerifier.InvalidTokenException.class, () -> verifier.verify(wrongIss)).getMessage())
                .contains("issuer");
    }

    @Test
    @DisplayName("JwtVerifier 拒绝尚未生效(nbf 在未来)的 JWT")
    void notYetValidRejected() {
        JwtVerifier verifier = new JwtVerifier(JWT_SECRET, "iam", 0, objectMapper);
        String future = signedJwt("{\"sub\":\"u\",\"iss\":\"iam\",\"aud\":\"safety\",\"nbf\":"
                + (nowSeconds() + 600) + ",\"exp\":" + (nowSeconds() + 3600) + "}", "HS256", JWT_SECRET);
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                JwtVerifier.InvalidTokenException.class, () -> verifier.verify(future)).getMessage())
                .contains("尚未生效");
    }

    @Test
    @DisplayName("合法验签 JWT：读放行、写放行，sub 写入请求属性")
    void validJwtPasses() throws Exception {
        SecurityInterceptor interceptor = enabledInterceptor();
        String jwt = signedJwt("{\"sub\":\"admin_01\",\"iss\":\"iam\",\"roles\":[\"ADMIN\"],\"aud\":\"safety\",\"exp\":"
                + (nowSeconds() + 3600) + "}", "HS256", JWT_SECRET);

        MockHttpServletRequest read = new MockHttpServletRequest("GET", "/api/v1/fences");
        read.addHeader("Authorization", "Bearer " + jwt);
        MockHttpServletResponse readResp = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(read, readResp, new Object())).isTrue();
        assertThat(read.getAttribute(SecurityInterceptor.ATTR_USER_SUB)).isEqualTo("admin_01");

        MockHttpServletRequest write = new MockHttpServletRequest("POST", "/api/v1/fences");
        write.addHeader("Authorization", "Bearer " + jwt);
        MockHttpServletResponse writeResp = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(write, writeResp, new Object())).isTrue();
    }

    @Test
    @DisplayName("64 字节密钥下 HS512 合法签名接受；头声明 HS512 但用 HS256 签名则拒绝")
    void customAlgorithmHandling() throws Exception {
        String longSecret = "scs-unit-test-hs512-secret-0123456789abcdefghijklmnopqrstuvwxyz-EXTRA";
        SecurityInterceptor interceptor = new SecurityInterceptor(
                true, API_KEY, "", longSecret, "iam", 60, "", "", AUDIENCE, 3000, objectMapper);
        String hs512 = signedJwt("{\"sub\":\"admin_01\",\"iss\":\"iam\",\"roles\":[\"ADMIN\"],\"aud\":\"safety\",\"exp\":"
                + (nowSeconds() + 3600) + "}", "HS512", longSecret);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/fences");
        req.addHeader("Authorization", "Bearer " + hs512);
        assertThat(interceptor.preHandle(req, new MockHttpServletResponse(), new Object())).isTrue();

        String[] parts = hs512.split("\\.");
        String mismatched = parts[0] + "." + parts[1] + "." + hmacB64("HS256", longSecret, parts[0] + "." + parts[1]);
        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/api/v1/fences");
        req2.addHeader("Authorization", "Bearer " + mismatched);
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req2, resp2, new Object())).isFalse();
        assertThat(resp2.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("S3只读角色硬否决：读接口放行，写接口 403 FORBIDDEN_READONLY_ROLE 并带 traceId")
    void readOnlyRoleAllowedOnReadBlockedOnWrite() throws Exception {
        SecurityInterceptor interceptor = enabledInterceptor();
        String readOnlyJwt = signedJwt(
                "{\"sub\":\"auditor_01\",\"iss\":\"iam\",\"roles\":[\"PLATFORM_AUDITOR_READONLY\"],\"aud\":\"safety\",\"exp\":"
                        + (nowSeconds() + 3600) + "}", "HS256", JWT_SECRET);

        MockHttpServletRequest readReq = new MockHttpServletRequest("GET", "/api/v1/overview/summary");
        readReq.addHeader("Authorization", "Bearer " + readOnlyJwt);
        MockHttpServletResponse readResp = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(readReq, readResp, new Object())).isTrue();
        assertThat(readReq.getAttribute(SecurityInterceptor.ATTR_USER_SUB)).isEqualTo("auditor_01");

        MockHttpServletRequest writeReq = new MockHttpServletRequest("POST", "/api/v1/alerts/ALT-001/handle");
        writeReq.addHeader("Authorization", "Bearer " + readOnlyJwt);
        writeReq.addHeader("X-Trace-Id", "9f8a3c21-test-42e1-8888-91b40e32f012");
        MockHttpServletResponse writeResp = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(writeReq, writeResp, new Object())).isFalse();
        assertThat(writeResp.getStatus()).isEqualTo(403);
        assertThat(writeResp.getHeader("X-Trace-Id")).isEqualTo("9f8a3c21-test-42e1-8888-91b40e32f012");
        assertThat(writeResp.getContentAsString()).contains("FORBIDDEN_READONLY_ROLE");
        assertThat(writeResp.getContentAsString()).contains("PLATFORM_AUDITOR_READONLY");
    }

    @Test
    @DisplayName("S3跨Scope隔离：受众为 wms 的合法 JWT 访问 SCS 返回 403 FORBIDDEN_SCOPE_MISMATCH")
    void crossScopeRejected() throws Exception {
        SecurityInterceptor interceptor = enabledInterceptor();
        String wmsJwt = signedJwt("{\"sub\":\"wms_user\",\"iss\":\"iam\",\"roles\":[\"OPERATOR\"],\"aud\":\"wms\",\"exp\":"
                + (nowSeconds() + 3600) + "}", "HS256", JWT_SECRET);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/alerts");
        req.addHeader("Authorization", "Bearer " + wmsJwt);
        req.addHeader("X-Trace-Id", "trace-cross-scope-001");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isFalse();
        assertThat(resp.getStatus()).isEqualTo(403);
        assertThat(resp.getContentAsString()).contains("FORBIDDEN_SCOPE_MISMATCH");
    }

    @Test
    @DisplayName("S3过期Token拦截：exp 在过去的已验签 token 返回 401")
    void expiredTokenRejected() throws Exception {
        SecurityInterceptor interceptor = enabledInterceptor();
        String expiredJwt = signedJwt("{\"sub\":\"user_exp\",\"iss\":\"iam\",\"roles\":[\"OPERATOR\"],\"aud\":\"safety\",\"exp\":"
                + (nowSeconds() - 3600) + "}", "HS256", JWT_SECRET);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/alerts");
        req.addHeader("Authorization", "Bearer " + expiredJwt);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("过期");
    }

    @Test
    @DisplayName("SEC-3: 鉴权开启但既无 JWT 密钥也无 IAM 验票配置时启动失败")
    void missingAuthenticationPathFailsFast() {
        SecurityInterceptor noPath = interceptor(true, "");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, noPath::validateConfiguration);

        SecurityInterceptor noApiKey = new SecurityInterceptor(
                true, "", "", JWT_SECRET, "iam", 60, "", "", AUDIENCE, 3000, objectMapper);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, noApiKey::validateConfiguration);

        SecurityInterceptor valid = enabledInterceptor();
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(valid::validateConfiguration);

        SecurityInterceptor disabled = interceptor(false, "");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(disabled::validateConfiguration);
    }

    @Test
    @DisplayName("配置了 IAM 验票但未配 JWT 密钥时允许启动（票据通道可用）")
    void iamTicketPathSatisfiesStartup() {
        SecurityInterceptor iamOnly = new SecurityInterceptor(
                true, API_KEY, "", "", "", 60,
                "http://127.0.0.1:18086", "unit-test-service-token-0123456789", "safety", 3000, objectMapper);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(iamOnly::validateConfiguration);
    }

    @Test
    @DisplayName("X-API-Key 合法时放行（边缘节点）")
    void validApiKeyPasses() throws Exception {
        SecurityInterceptor interceptor = enabledInterceptor();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ai-events/ingest");
        req.addHeader("X-API-Key", API_KEY);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();
        assertThat(req.getAttribute(SecurityInterceptor.ATTR_USER_SUB)).isEqualTo("edge-node");
    }

    @Test
    @DisplayName("X-User-Roles 请求头不再提升权限（网关透传头信任已移除）")
    void gatewayRoleHeaderDoesNotGrantPrivilege() throws Exception {
        SecurityInterceptor interceptor = enabledInterceptor();
        String readOnlyJwt = signedJwt(
                "{\"sub\":\"auditor_02\",\"iss\":\"iam\",\"roles\":[\"PLATFORM_AUDITOR_READONLY\"],\"aud\":\"safety\",\"exp\":"
                        + (nowSeconds() + 3600) + "}", "HS256", JWT_SECRET);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/fences");
        req.addHeader("Authorization", "Bearer " + readOnlyJwt);
        req.addHeader("X-User-Roles", "ADMIN");
        req.addHeader("X-User-Permissions", "fence:write");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isFalse();
        assertThat(resp.getStatus()).isEqualTo(403);
        assertThat(resp.getContentAsString()).contains("FORBIDDEN_READONLY_ROLE");
    }

    @Test
    @DisplayName("G5: 密钥长度不足以支持 HS512 时拒绝（算法降级防护）")
    void hs512RequiresLongerKey() {
        // 32 字节密钥可过构造，但不足以用于 HS512
        JwtVerifier verifier = new JwtVerifier(JWT_SECRET, "iam", 60, objectMapper);
        String hs512 = signedJwt("{\"sub\":\"u\",\"iss\":\"iam\",\"aud\":\"safety\",\"exp\":"
                + (nowSeconds() + 3600) + "}", "HS512", JWT_SECRET);
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                JwtVerifier.InvalidTokenException.class, () -> verifier.verify(hs512)).getMessage())
                .contains("HS512");
    }

    @Test
    @DisplayName("G5: 缺少 exp 的战票被拒（旧实现视为永不过期）")
    void missingExpRejected() {
        JwtVerifier verifier = new JwtVerifier(JWT_SECRET, "iam", 60, objectMapper);
        String noExp = signedJwt("{\"sub\":\"u\",\"iss\":\"iam\",\"aud\":\"safety\"}", "HS256", JWT_SECRET);
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                JwtVerifier.InvalidTokenException.class, () -> verifier.verify(noExp)).getMessage())
                .contains("exp");
    }

    @Test
    @DisplayName("G5: 缺少 aud 的 JWT 被拒")
    void missingAudRejected() {
        JwtVerifier verifier = new JwtVerifier(JWT_SECRET, "iam", 60, objectMapper);
        String noAud = signedJwt("{\"sub\":\"u\",\"iss\":\"iam\",\"exp\":"
                + (nowSeconds() + 3600) + "}", "HS256", JWT_SECRET);
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                JwtVerifier.InvalidTokenException.class, () -> verifier.verify(noAud)).getMessage())
                .contains("aud");
    }

    @Test
    @DisplayName("G5: issuer 留空则无法构造验签器（不允许选配）")
    void blankIssuerFailsClosed() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> new JwtVerifier(JWT_SECRET, "", 60, objectMapper)).getMessage())
                .contains("issuer");
    }

    @Test
    @DisplayName("G5: 已配置 jwt-secret 但 issuer 为空时构造即失败（不允许选配）")
    void secretWithoutIssuerFailsStartup() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> new SecurityInterceptor(
                        true, API_KEY, "", JWT_SECRET, "", 60, "", "", AUDIENCE, 3000, objectMapper));
    }

    @Test
    @DisplayName("G5: 超长 token 被拒")
    void oversizedTokenRejected() {
        JwtVerifier verifier = new JwtVerifier(JWT_SECRET, "iam", 60, objectMapper);
        String huge = "a".repeat(9000) + ".b.c";
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                JwtVerifier.InvalidTokenException.class, () -> verifier.verify(huge)).getMessage())
                .contains("过长");
    }

    // ---- helpers ----

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unsignedJwt(String payloadJson, String alg) {
        return b64("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}") + "." + b64(payloadJson) + ".";
    }

    private static String signedJwt(String payloadJson, String alg, String secret) {
        String signingInput = b64("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}") + "." + b64(payloadJson);
        return signingInput + "." + hmacB64(alg, secret, signingInput);
    }

    private static String hmacB64(String alg, String secret, String signingInput) {
        String jca = switch (alg) {
            case "HS256" -> "HmacSHA256";
            case "HS384" -> "HmacSHA384";
            case "HS512" -> "HmacSHA512";
            default -> throw new IllegalArgumentException(alg);
        };
        try {
            Mac mac = Mac.getInstance(jca);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), jca));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
