package com.bproject.safety.common.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bproject.safety.config.ScsIamTicketValidator;
import com.bproject.safety.config.ScsLocalPolicy;
import com.bproject.safety.config.ScsSessionRegistry;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

/**
 * IAM 抖动与"会话撤销"必须分开处理：
 * <ul>
 *   <li>IAM 不可用 → 不注销会话，按最近授权继续投递（宽限期内），否则暂停本次投递但保持连接；</li>
 *   <li>IAM 明确判定失效 / 本地已撤销 → 才允许断开连接。</li>
 * </ul>
 * 旧实现把两者都当成撤销，IAM 一次超时就会把全部在线连接踢下线并触发重连风暴。
 *
 * <p>注册表是进程级静态状态且无公开 reset，本测试每个用例使用唯一 id 避免相互污染。
 */
@DisplayName("IAM 抖动不得注销实时会话")
class IamOutageResilienceTest {

  private String local;
  private String sid;
  private ScsIamTicketValidator iam;
  private ScsLocalPolicy policy;
  private WebSocketSession socket;

  @BeforeEach
  void setUp() {
    local = "local-" + UUID.randomUUID();
    sid = "iam-sid-" + UUID.randomUUID();
    iam = mock(ScsIamTicketValidator.class);
    policy = mock(ScsLocalPolicy.class);
    socket = mock(WebSocketSession.class);

    ScsSessionRegistry.bind(local, "user-1", sid, Set.of("OPERATOR"), Set.of("safety:module:alert:view"), "scs");
    var binding = ScsSessionRegistry.get(local);
    when(socket.getAttributes()).thenReturn(Map.of("SCS_WS_BINDING", binding));
  }

  @Test
  @DisplayName("IAM 不可用但宽限期内：沿用最近一次授权，不抛异常、不断连")
  void iamOutageWithinGraceKeepsLastKnownActor() {
    var actor = new ScsLocalPolicy.Actor("user-1", "李娜", Set.of("OPERATOR"),
        Set.of("safety:module:alert:view"), false, false);
    when(iam.current(anyString(), anyString())).thenReturn(Map.of("sub", "user-1"));
    when(policy.resolve(anyMap())).thenReturn(actor);

    var service = new LiveSessionAuthorization(iam, policy, true, 600);
    assertSame(actor, service.current(socket), "首次成功解析应返回该授权");

    when(iam.current(anyString(), anyString()))
        .thenThrow(new ScsIamTicketValidator.IamUnavailableException("IAM 暂不可用"));
    assertSame(actor, service.current(socket), "宽限期内应沿用最近授权而不是断连");
    assertNeverClosed(socket);
  }

  @Test
  @DisplayName("IAM 不可用且无缓存授权：暂停本次投递，仍不关闭连接")
  void iamOutageWithoutCacheSuspendsDeliveryOnly() {
    when(iam.current(anyString(), anyString()))
        .thenThrow(new ScsIamTicketValidator.IamUnavailableException("IAM 暂不可用"));
    var service = new LiveSessionAuthorization(iam, policy, true, 600);

    assertThrows(LiveSessionAuthorization.DeliverySuspendedException.class, () -> service.current(socket));
    assertNeverClosed(socket);
  }

  @Test
  @DisplayName("宽限期为 0 时 IAM 不可用即暂停投递（不沿用缓存）")
  void zeroGraceSuspendsImmediately() {
    var actor = new ScsLocalPolicy.Actor("user-1", "李娜", Set.of("OPERATOR"), Set.of(), false, false);
    when(iam.current(anyString(), anyString())).thenReturn(Map.of("sub", "user-1"));
    when(policy.resolve(anyMap())).thenReturn(actor);

    var service = new LiveSessionAuthorization(iam, policy, true, 0);
    assertNotNull(service.current(socket));

    when(iam.current(anyString(), anyString()))
        .thenThrow(new ScsIamTicketValidator.IamUnavailableException("IAM 暂不可用"));
    assertThrows(LiveSessionAuthorization.DeliverySuspendedException.class, () -> service.current(socket));
  }

  @Test
  @DisplayName("IAM 明确判定会话失效：仍然抛撤销异常（可断连）")
  void explicitRevocationStillCloses() {
    when(iam.current(anyString(), anyString()))
        .thenThrow(new ScsIamTicketValidator.InvalidTicketException("IAM会话已失效"));
    var service = new LiveSessionAuthorization(iam, policy, true, 600);

    var thrown = assertThrows(RuntimeException.class, () -> service.current(socket));
    assertFalse(thrown instanceof LiveSessionAuthorization.DeliverySuspendedException,
        "明确撤销不应被当成 IAM 抖动而放行");
  }

  @Test
  @DisplayName("本地注册表已撤销：立即断开，不查 IAM")
  void localRevocationShortCircuits() {
    ScsSessionRegistry.revokeByIamSid(sid);
    var service = new LiveSessionAuthorization(iam, policy, true, 600);

    assertThrows(IllegalStateException.class, () -> service.current(socket));
    verify(iam, never()).current(anyString(), anyString());
  }

  @Test
  @DisplayName("forget 清理缓存后不再沿用旧授权")
  void forgetDropsCache() {
    var actor = new ScsLocalPolicy.Actor("user-1", "李娜", Set.of("OPERATOR"), Set.of(), false, false);
    when(iam.current(anyString(), anyString())).thenReturn(Map.of("sub", "user-1"));
    when(policy.resolve(anyMap())).thenReturn(actor);

    var service = new LiveSessionAuthorization(iam, policy, true, 600);
    service.current(socket);
    service.forget(local);

    when(iam.current(anyString(), anyString()))
        .thenThrow(new ScsIamTicketValidator.IamUnavailableException("IAM 暂不可用"));
    assertThrows(LiveSessionAuthorization.DeliverySuspendedException.class, () -> service.current(socket));
  }

  @Test
  @DisplayName("IAM 超时映射为 IamUnavailable，而非撤销")
  void validatorSeparatesOutageFromRevocation() {
    assertEquals(true, new ScsIamTicketValidator.IamUnavailableException("x")
        instanceof ScsIamTicketValidator.InvalidTicketException,
        "IamUnavailable 应继承 InvalidTicket，保证既有 catch 分支仍能兜住");
  }

  /** WebSocketSession.close 声明 IOException，用辅助方法包住，避免每个用例都 throws。 */
  private static void assertNeverClosed(WebSocketSession session) {
    try {
      verify(session, never()).close(org.mockito.ArgumentMatchers.any());
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
  }
}
