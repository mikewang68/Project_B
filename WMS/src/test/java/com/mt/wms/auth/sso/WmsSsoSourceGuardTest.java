package com.mt.wms.auth.sso;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * G5B 止血：exchange 来源白名单。
 *
 * <p>锁定两条关键规则：
 *
 * <ol>
 *   <li>直连请求自带的 {@code X-Forwarded-For} <b>不得</b>被采信；
 *   <li>空白名单是 fail-closed（拒绝全部），不是放行。
 * </ol>
 */
@DisplayName("G5B exchange 来源白名单")
class WmsSsoSourceGuardTest {

    private WmsSsoSourceGuard guard(String cidrs, String proxies) {
        WmsSsoProperties props = new WmsSsoProperties();
        set(props, "exchangeAllowedCidrs", cidrs);
        set(props, "trustedProxies", proxies);
        return new WmsSsoSourceGuard(props);
    }

    private static void set(WmsSsoProperties props, String field, String value) {
        try {
            var f = WmsSsoProperties.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(props, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MockHttpServletRequest from(String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/sso/exchange");
        req.setRemoteAddr(ip);
        return req;
    }

    @Test
    @DisplayName("默认配置仅允许回环")
    void defaultAllowsLoopbackOnly() {
        WmsSsoSourceGuard g = guard("127.0.0.1/32,::1/128", "");
        assertTrue(g.isAllowed(from("127.0.0.1")));
        assertFalse(g.isAllowed(from("192.0.2.74")));
    }

    @Test
    @DisplayName("配置为 BFF 主机后放行该地址，其它地址仍拒绝")
    void allowsConfiguredBffHost() {
        WmsSsoSourceGuard g = guard("127.0.0.1/32,192.0.2.74/32", "");
        assertTrue(g.isAllowed(from("192.0.2.74")));
        assertFalse(g.isAllowed(from("192.0.2.99")));
    }

    @Test
    @DisplayName("CIDR 段匹配")
    void matchesCidrRange() {
        WmsSsoSourceGuard g = guard("10.20.0.0/16", "");
        assertTrue(g.isAllowed(from("10.20.5.7")));
        assertTrue(g.isAllowed(from("10.20.255.255")));
        assertFalse(g.isAllowed(from("10.21.0.1")));
    }

    @Test
    @DisplayName("空白名单 fail-closed：拒绝全部，不再表示放行")
    void emptyAllowlistDeniesEverything() {
        WmsSsoSourceGuard g = guard("", "");
        assertFalse(g.isAllowed(from("127.0.0.1")), "空配置必须拒绝回环");
        assertFalse(g.isAllowed(from("192.0.2.74")), "空配置必须拒绝内网");
        assertFalse(g.isAllowed(from("203.0.113.9")), "空配置必须拒绝外部");
    }

    @Test
    @DisplayName("直连伪造 X-Forwarded-For 不得被采信（核心修复）")
    void directClientCannotSpoofViaForwardedFor() {
        // 只允许 127.0.0.1；攻击者从外部直连并自称来自 127.0.0.1
        WmsSsoSourceGuard g = guard("127.0.0.1/32", "");
        MockHttpServletRequest spoof = from("192.0.2.200");
        spoof.addHeader("X-Forwarded-For", "127.0.0.1");

        assertFalse(g.isAllowed(spoof),
                "非受信 peer 的 X-Forwarded-For 必须被忽略，否则可冒充本机");
    }

    @Test
    @DisplayName("受信反代转发的 XFF 才被采信")
    void trustedProxyForwardedForIsHonoured() {
        // 本机作为受信反代，转发真实客户端 192.0.2.74
        WmsSsoSourceGuard g = guard("192.0.2.74/32", "127.0.0.1/32");
        MockHttpServletRequest viaProxy = from("127.0.0.1");
        viaProxy.addHeader("X-Forwarded-For", "192.0.2.74");

        assertTrue(g.isAllowed(viaProxy), "受信反代转发的真实来源应在允许列表内");
    }

    @Test
    @DisplayName("受信反代转发的外来地址仍被拒")
    void trustedProxyForwardingOutsiderIsDenied() {
        WmsSsoSourceGuard g = guard("192.0.2.74/32", "127.0.0.1/32");
        MockHttpServletRequest viaProxy = from("127.0.0.1");
        viaProxy.addHeader("X-Forwarded-For", "203.0.113.9");

        assertFalse(g.isAllowed(viaProxy));
    }

    @Test
    @DisplayName("未配置受信反代时，即使是本机也不要信 XFF")
    void noTrustedProxyMeansIgnoreForwardedFor() {
        WmsSsoSourceGuard g = guard("192.0.2.74/32", "");
        MockHttpServletRequest req = from("127.0.0.1");
        req.addHeader("X-Forwarded-For", "192.0.2.74");

        assertFalse(g.isAllowed(req),
                "未声明受信反代时，XFF 一律不可信；真实 peer 127.0.0.1 不在允许列表");
    }

    @Test
    @DisplayName("无法解析的来源地址按拒绝处理")
    void unresolvableSourceDenied() {
        WmsSsoSourceGuard g = guard("127.0.0.1/32", "");
        assertFalse(g.isAllowed(from("not-an-ip")));
        assertFalse(g.isAllowed(from("")));
    }

    @Test
    void trustedProxyCannotPromoteAnAttackerControlledLeftPrefix() {
        WmsSsoSourceGuard g = guard("127.0.0.1/32", "10.0.0.1/32");
        var request = from("10.0.0.1");
        request.addHeader("X-Forwarded-For", "127.0.0.1, 203.0.113.9");
        assertFalse(g.isAllowed(request), "Closest untrusted hop is the client; ignore its claimed prefix");
    }

    @Test
    void multipleTrustedProxiesRetainTheActualAllowedClient() {
        WmsSsoSourceGuard g = guard("192.0.2.7/32", "10.0.0.0/24");
        var request = from("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 192.0.2.7, 10.0.0.2");
        assertTrue(g.isAllowed(request));
    }

    @Test
    void invalidClosestForwardedHopIsRejectedWithoutDnsLookup() {
        WmsSsoSourceGuard g = guard("127.0.0.1/32", "10.0.0.1/32");
        var request = from("10.0.0.1");
        request.addHeader("X-Forwarded-For", "127.0.0.1, localhost");
        assertFalse(g.isAllowed(request));
    }
}
