package com.bproject.safety.config;

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
 * G6：票据一次性交换到本地会话后的撤销语义。
 *
 * <p>锁定：撤销必须不依赖会话记录是否仍然存在（进程内表可能被清理），
 * 且同一 IAM sid 下的全部本地会话一起失效。
 */
@DisplayName("G6 SCS 本地会话注册表")
class ScsSessionRegistryTest {

  private static final String SID = "iam-sid-0001";
  private static final String LOCAL_A = "local-session-aaa";
  private static final String LOCAL_B = "local-session-bbb";

  @BeforeEach
  void setUp() {
    ScsSessionRegistry.resetForTests();
  }

  @AfterEach
  void tearDown() {
    ScsSessionRegistry.resetForTests();
  }

  @Test
  @DisplayName("绑定后可按本地会话取到主体与角色")
  void bindAndRetrieve() {
    ScsSessionRegistry.bind(LOCAL_A, "user-1", SID, Set.of("OPERATOR"), Set.of("alert:read"), "safety");

    ScsSessionRegistry.Session s = ScsSessionRegistry.get(LOCAL_A);
    assertNotNull(s);
    assertEquals("user-1", s.iamSubject());
    assertEquals(SID, s.iamSid());
    assertEquals(Set.of("OPERATOR"), s.roles());
    assertFalse(ScsSessionRegistry.isSessionRevoked(LOCAL_A));
  }

  @Test
  @DisplayName("按 IAM sid 撤销后本地会话立即进入撤销态")
  void revokeByIamSid() {
    ScsSessionRegistry.bind(LOCAL_A, "user-1", SID, Set.of(), Set.of(), "safety");

    int affected = ScsSessionRegistry.revokeByIamSid(SID);

    assertEquals(1, affected);
    assertTrue(ScsSessionRegistry.isSessionRevoked(LOCAL_A));
  }

  @Test
  @DisplayName("同一 IAM sid 的多个本地会话全部失效（多标签场景）")
  void revokeAffectsAllLocalSessions() {
    ScsSessionRegistry.bind(LOCAL_A, "user-1", SID, Set.of(), Set.of(), "safety");
    ScsSessionRegistry.bind(LOCAL_B, "user-1", SID, Set.of(), Set.of(), "safety");

    int affected = ScsSessionRegistry.revokeByIamSid(SID);

    assertEquals(2, affected);
    assertTrue(ScsSessionRegistry.isSessionRevoked(LOCAL_A));
    assertTrue(ScsSessionRegistry.isSessionRevoked(LOCAL_B));
  }

  @Test
  @DisplayName("撤销幂等：重复调用不报错，结果一致")
  void revokeIsIdempotent() {
    ScsSessionRegistry.bind(LOCAL_A, "user-1", SID, Set.of(), Set.of(), "safety");

    int first = ScsSessionRegistry.revokeByIamSid(SID);
    int second = ScsSessionRegistry.revokeByIamSid(SID);

    assertEquals(1, first);
    assertTrue(second >= 0);
    assertTrue(ScsSessionRegistry.isSessionRevoked(LOCAL_A));
  }

  @Test
  @DisplayName("会话记录被清理后，撤销判据仍然成立")
  void revokeSurvivesRecordRemoval() {
    ScsSessionRegistry.bind(LOCAL_A, "user-1", SID, Set.of(), Set.of(), "safety");
    ScsSessionRegistry.revokeByIamSid(SID);
    ScsSessionRegistry.unbind(LOCAL_A);

    assertNull(ScsSessionRegistry.get(LOCAL_A));
    assertTrue(ScsSessionRegistry.isSessionRevoked(LOCAL_A),
        "绑定被清理后，撤销判据仍必须成立");
  }

  @Test
  @DisplayName("未撤销的会话不误判；其它 sid 的撤销不影响本会话")
  void unrelatedSidDoesNotAffect() {
    ScsSessionRegistry.bind(LOCAL_A, "user-1", SID, Set.of(), Set.of(), "safety");
    ScsSessionRegistry.revokeByIamSid("other-sid-999");

    assertFalse(ScsSessionRegistry.isSessionRevoked(LOCAL_A));
  }

  @Test
  @DisplayName("空输入无副作用")
  void blankInputs() {
    assertEquals(0, ScsSessionRegistry.revokeByIamSid(null));
    assertEquals(0, ScsSessionRegistry.revokeByIamSid("  "));
    assertFalse(ScsSessionRegistry.isSessionRevoked(null));
    ScsSessionRegistry.bind(null, "u", SID, Set.of(), Set.of(), "safety");
    ScsSessionRegistry.bind("  ", "u", SID, Set.of(), Set.of(), "safety");
    assertEquals(0, ScsSessionRegistry.size());
  }
}
