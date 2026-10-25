package com.bproject.safety.common.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.bproject.safety.config.SecurityInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

/**
 * review-#1：/ws/live 握手鉴权测试——实时事件流不再匿名可达，
 * 身份校验与 REST 同源（会话 Cookie / API-Key / Bearer / ?token=）。
 */
@DisplayName("review-#1: WS 握手鉴权拦截器测试")
class WsHandshakeAuthInterceptorTest {

    private static final String API_KEY = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID();
    private static final String JWT_SECRET = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID();
    private static final String AUDIENCE = "safety";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private SecurityInterceptor security() {
        return new SecurityInterceptor(true, API_KEY, "", JWT_SECRET, "iam", 60,
                "", "", AUDIENCE, 3000, objectMapper);
    }

    private String validJwt() {
        long exp = java.time.Instant.now().getEpochSecond() + 600;
        String payload = "{\"sub\":\"admin_01\",\"iss\":\"iam\",\"roles\":[\"ADMIN\"],"
                + "\"aud\":\"safety\",\"exp\":" + exp + "}";
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String signingInput = header + "." + b64(payload);
        return signingInput + "." + hmac(signingInput);
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private String hmac(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Handshake(MockHttpServletRequest request, MockHttpServletResponse response,
                             Map<String, Object> attributes) {
        ServletServerHttpRequest serverRequest() {
            return new ServletServerHttpRequest(request);
        }

        ServletServerHttpResponse serverResponse() {
            return new ServletServerHttpResponse(response);
        }
    }

    private Handshake handshake(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        return new Handshake(request, new MockHttpServletResponse(), new HashMap<>());
    }

    @Test
    @DisplayName("开关关闭：无凭据也放行（兼容灰度期）")
    void disabledAllowsAll() {
        WsHandshakeAuthInterceptor interceptor = new WsHandshakeAuthInterceptor(security(), false);
        Handshake hs = handshake("GET", "/ws/live");
        assertThat(interceptor.beforeHandshake(
                hs.serverRequest(), hs.serverResponse(), null, hs.attributes())).isTrue();
    }

    @Test
    @DisplayName("开关开启且无凭据：握手被拒并写 401")
    void rejectsAnonymousWhenEnabled() {
        WsHandshakeAuthInterceptor interceptor = new WsHandshakeAuthInterceptor(security(), true);
        Handshake hs = handshake("GET", "/ws/live");
        assertThat(interceptor.beforeHandshake(
                hs.serverRequest(), hs.serverResponse(), null, hs.attributes())).isFalse();
        assertThat(hs.response().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Authorization Bearer 头：握手通过且主体属性透传到 WS 会话")
    void acceptsBearerHeader() {
        WsHandshakeAuthInterceptor interceptor = new WsHandshakeAuthInterceptor(security(), true);
        Handshake hs = handshake("GET", "/ws/live");
        hs.request().addHeader("Authorization", "Bearer " + validJwt());

        assertThat(interceptor.beforeHandshake(
                hs.serverRequest(), hs.serverResponse(), null, hs.attributes())).isTrue();
        assertThat(hs.attributes())
                .containsEntry(SecurityInterceptor.ATTR_USER_SUB, "admin_01");
    }

    @Test
    @DisplayName("?token= 查询参数：包装为 Bearer 后同源校验通过")
    void acceptsTokenQueryParam() {
        WsHandshakeAuthInterceptor interceptor = new WsHandshakeAuthInterceptor(security(), true);
        Handshake hs = handshake("GET", "/ws/live");
        hs.request().setMethod("GET");
        hs.request().setRequestURI("/ws/live");
        hs.request().setParameter(WsHandshakeAuthInterceptor.TOKEN_PARAM, validJwt());

        assertThat(interceptor.beforeHandshake(
                hs.serverRequest(), hs.serverResponse(), null, hs.attributes())).isTrue();
        assertThat(hs.attributes()).containsEntry(SecurityInterceptor.ATTR_USER_SUB, "admin_01");
    }

    @Test
    @DisplayName("伪造的会话属性不能代替已登记的 SCS 会话")
    void rejectsUnregisteredSessionAttributes() {
        WsHandshakeAuthInterceptor interceptor = new WsHandshakeAuthInterceptor(security(), true);
        Handshake hs = handshake("GET", "/ws/live");
        MockHttpSession session = (MockHttpSession) hs.request().getSession(true);
        session.setAttribute("SCS_SSO_ESTABLISHED", Boolean.TRUE);
        session.setAttribute("SCS_SSO_SUBJECT", "op_1001");
        session.setAttribute("SCS_SSO_ROLES", java.util.Set.of("OPERATOR"));
        session.setAttribute("SCS_SSO_PERMISSIONS", java.util.Set.of());
        session.setAttribute("SCS_SSO_SCOPE", "safety");

        assertThat(interceptor.beforeHandshake(
                hs.serverRequest(), hs.serverResponse(), null, hs.attributes())).isFalse();
        assertThat(hs.attributes()).doesNotContainKey(SecurityInterceptor.ATTR_USER_SUB);
    }

    @Test
    @DisplayName("设备采集凭据不能连接业务 WebSocket")
    void rejectsIngestionCredentialOnBusinessSocket() {
        WsHandshakeAuthInterceptor interceptor = new WsHandshakeAuthInterceptor(security(), true);
        Handshake hs = handshake("GET", "/ws/live");
        hs.request().addHeader("X-API-Key", API_KEY);

        assertThat(interceptor.beforeHandshake(
                hs.serverRequest(), hs.serverResponse(), null, hs.attributes())).isFalse();
    }

    @Test
    @DisplayName("伪造 token：握手被拒（与 REST 同强度校验）")
    void rejectsForgedToken() {
        WsHandshakeAuthInterceptor interceptor = new WsHandshakeAuthInterceptor(security(), true);
        Handshake hs = handshake("GET", "/ws/live");
        hs.request().addHeader("Authorization", "Bearer " + validJwt() + "tampered");

        assertThat(interceptor.beforeHandshake(
                hs.serverRequest(), hs.serverResponse(), null, hs.attributes())).isFalse();
        assertThat(hs.response().getStatus()).isEqualTo(401);
    }
}
