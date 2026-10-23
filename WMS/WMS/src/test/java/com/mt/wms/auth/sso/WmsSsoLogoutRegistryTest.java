package com.mt.wms.auth.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * G5-4 回归：撤销后旧会话必须被识别，且绑定不能被撤销动作本身删除。
 *
 * <p>这个测试类锁定的正是原缺陷：
 * {@code revoke} 先打撤销标记、随后解绑，导致 Guard 找不到绑定而放行。
 */
@DisplayName("G5-4 WMS SSO 会话撤销注册表")
class WmsSsoLogoutRegistryTest {

  private static final String IAM_SID = "iam-sid-0001";
  private static final String WMS_SID = "wms-session-aaaa";

  @BeforeEach
  void resetState() {
    // 注册表是静态的，不清理会让撤销标记跨用例泄漏
    WmsSsoLogoutRegistry.resetForTests();
  }

  @AfterEach
  void cleanup() {
    WmsSsoLogoutRegistry.resetForTests();
  }

  @Test
  @DisplayName("撤销后绑定仍存在且被标记，本地 session 进入撤销集合")
  void revokeKeepsBindingAndMarksIt() {
    WmsSsoLogoutRegistry.bind(WMS_SID, IAM_SID, "admin");

    assertFalse(WmsSsoLogoutRegistry.isSessionRevoked(WMS_SID));
    Set<String> affected = WmsSsoLogoutRegistry.revokeByIamSid(IAM_SID);

    assertEquals(Set.of(WMS_SID), affected, "撤销应返回受影响的本地会话");
    assertTrue(WmsSsoLogoutRegistry.isRevoked(IAM_SID), "IAM sid 应标记为已撤销");
    assertTrue(WmsSsoLogoutRegistry.isSessionRevoked(WMS_SID), "本地会话应进入撤销集合");

    WmsSsoLogoutRegistry.Binding binding = WmsSsoLogoutRegistry.get(WMS_SID);
    assertNotNull(binding, "撤销不得删除绑定，否则 Guard 会误判为原生会话");
    assertTrue(binding.revoked(), "绑定应带撤销标志");
  }

  @Test
  @DisplayName("同一 IAM sid 的多个本地会话全部失效")
  void revokeAffectsAllSessionsOfSameIamSid() {
    WmsSsoLogoutRegistry.bind(WMS_SID, IAM_SID, "admin");
    WmsSsoLogoutRegistry.bind("wms-session-bbbb", IAM_SID, "admin");

    Set<String> affected = WmsSsoLogoutRegistry.revokeByIamSid(IAM_SID);

    assertEquals(2, affected.size());
    assertTrue(WmsSsoLogoutRegistry.isSessionRevoked(WMS_SID));
    assertTrue(WmsSsoLogoutRegistry.isSessionRevoked("wms-session-bbbb"));
  }

  @Test
  @DisplayName("撤销幂等：重复调用结果一致，不抛异常")
  void revokeIsIdempotent() {
    WmsSsoLogoutRegistry.bind(WMS_SID, IAM_SID, "admin");

    Set<String> first = WmsSsoLogoutRegistry.revokeByIamSid(IAM_SID);
    Set<String> second = WmsSsoLogoutRegistry.revokeByIamSid(IAM_SID);

    assertEquals(first, second);
    assertTrue(WmsSsoLogoutRegistry.isSessionRevoked(WMS_SID));
  }

  @Test
  @DisplayName("撤销前先清理绑定：本地会话仍被判为已撤销（不依赖绑定）")
  void revokeSurvivesBindingRemoval() {
    WmsSsoLogoutRegistry.bind(WMS_SID, IAM_SID, "admin");
    WmsSsoLogoutRegistry.revokeByIamSid(IAM_SID);

    // 模拟 Guard 在销毁会话后清理绑定
    WmsSsoLogoutRegistry.unbind(WMS_SID);

    assertNull(WmsSsoLogoutRegistry.get(WMS_SID));
    assertTrue(
        WmsSsoLogoutRegistry.isSessionRevoked(WMS_SID),
        "绑定被清理后，撤销判据仍必须成立");
  }

  @Test
  @DisplayName("未撤销的会话不会被误判")
  void activeSessionIsNotRevoked() {
    WmsSsoLogoutRegistry.bind(WMS_SID, "iam-sid-other", "operator");

    assertFalse(WmsSsoLogoutRegistry.isSessionRevoked(WMS_SID));
    assertFalse(WmsSsoLogoutRegistry.isRevoked("iam-sid-other"));
  }

  @Test
  @DisplayName("空 sid / 空会话不产生副作用")
  void blankInputsAreIgnored() {
    assertTrue(WmsSsoLogoutRegistry.revokeByIamSid(null).isEmpty());
    assertTrue(WmsSsoLogoutRegistry.revokeByIamSid("  ").isEmpty());
    assertFalse(WmsSsoLogoutRegistry.isSessionRevoked(null));
    WmsSsoLogoutRegistry.bind(null, IAM_SID, "x");
    WmsSsoLogoutRegistry.bind("   ", IAM_SID, "x");
    assertFalse(WmsSsoLogoutRegistry.isSessionRevoked("   "));
  }
}
