package com.bdemo.iam.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SLO 通知器的纯逻辑测试（不依赖 Spring / 数据库 / 网络）。
 *
 * <p>锁定两条关键判定：
 *
 * <ol>
 *   <li>业务成功码的判定——<b>HTTP 200 不等于业务成功</b>，
 *       这正是本轮实测踩到的坑（代理丢头导致 revoke 返回 200 但业务失败）；
 *   <li>注销端点按受众逐个登记，不假设统一路径。
 * </ol>
 */
@DisplayName("IAM SLO 注销通知器")
class SsoLogoutNotifierTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("端点解析：按受众登记，支持分号分隔")
  void parseEndpoints() {
    Map<String, String> m = SsoLogoutNotifier.parseEndpoints(
        "wms=http://a/api/v1/auth/sso/revoke;scs=http://b/api/v1/auth/sso/revoke");
    assertEquals(2, m.size());
    assertEquals("http://a/api/v1/auth/sso/revoke", m.get("wms"));
    assertEquals("http://b/api/v1/auth/sso/revoke", m.get("scs"));
  }

  @Test
  @DisplayName("端点解析：空配置返回空表")
  void parseEndpointsEmpty() {
    assertTrue(SsoLogoutNotifier.parseEndpoints("").isEmpty());
    assertTrue(SsoLogoutNotifier.parseEndpoints(null).isEmpty());
  }

  @Test
  @DisplayName("端点解析：忽略非法项，保留合法项")
  void parseEndpointsSkipsInvalid() {
    Map<String, String> m = SsoLogoutNotifier.parseEndpoints("broken;wms=http://ok;=nokey");
    assertTrue(m.containsKey("wms"));
    assertEquals(1, m.size(), "无键或无值的项不应计入");
  }

  @Test
  @DisplayName("业务成功：code=0（数字）")
  void businessOkNumberCode() {
    assertTrue(SsoLogoutNotifier.businessOk("{\"code\":0,\"data\":{}}", MAPPER));
  }

  @Test
  @DisplayName("业务成功：code=\"0\" / \"OK\"（字符串）")
  void businessOkStringCode() {
    assertTrue(SsoLogoutNotifier.businessOk("{\"code\":\"0\"}", MAPPER));
    assertTrue(SsoLogoutNotifier.businessOk("{\"code\":\"OK\"}", MAPPER));
  }

  @Test
  @DisplayName("业务成功：ok=true / ok=false")
  void businessOkBoolean() {
    assertTrue(SsoLogoutNotifier.businessOk("{\"ok\":true}", MAPPER));
    assertFalse(SsoLogoutNotifier.businessOk("{\"ok\":false}", MAPPER));
  }

  @Test
  @DisplayName("HTTP 200 + 业务错误码必须判为失败")
  void businessErrorCodeIsFailure() {
    assertFalse(SsoLogoutNotifier.businessOk("{\"code\":\"SSO_FORBIDDEN\"}", MAPPER));
    assertFalse(SsoLogoutNotifier.businessOk("{\"code\":40301}", MAPPER));
    assertFalse(SsoLogoutNotifier.businessOk("{\"code\":\"SSO_TICKET_REJECTED\"}", MAPPER));
    assertFalse(SsoLogoutNotifier.businessOk("{\"code\":\"UNAUTHORIZED\"}", MAPPER));
  }

  @Test
  @DisplayName("无已知成功标志时保守判失败")
  void unknownBodyIsFailure() {
    assertFalse(SsoLogoutNotifier.businessOk("{\"something\":\"else\"}", MAPPER));
    assertFalse(SsoLogoutNotifier.businessOk("not-json", MAPPER));
  }

  @Test
  @DisplayName("空响应体视为成功（204 / 空 200 场景）")
  void emptyBodyIsSuccess() {
    assertTrue(SsoLogoutNotifier.businessOk("", MAPPER));
    assertTrue(SsoLogoutNotifier.businessOk(null, MAPPER));
  }
}
