package com.bproject.safety.common.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.bproject.safety.module.alert.dto.AlertRequests.AssignRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.ConfirmRequest;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.alert.seed.AlertDemoSeeder;
import com.bproject.safety.module.alert.service.AlertService;
import java.net.URI;
import java.time.Clock;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * /ws/live 端到端测试：ping/pong、写操作后多连接广播、断开后会话清理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LiveWebSocketTest {

    @LocalServerPort
    int port;
    @Autowired
    AlertService alertService;
    @Autowired
    AlertRepository repository;
    @Autowired
    WebSocketSessionRegistry registry;
    @Autowired
    Clock clock;

    private final StandardWebSocketClient client = new StandardWebSocketClient();
    private WebSocketSession sessionA;
    private WebSocketSession sessionB;
    private CollectHandler handlerA;
    private CollectHandler handlerB;

    static class CollectHandler extends TextWebSocketHandler {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.offer(message.getPayload());
        }
    }

    private WebSocketSession connect(CollectHandler handler) throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + port + "/ws/live");
        return client.execute(handler, null, uri).get(5, TimeUnit.SECONDS);
    }

    @BeforeEach
    void setUp() throws Exception {
        ((InMemoryAlertRepository) repository).clear();
        AlertDemoSeeder.buildSeeds(clock).forEach(repository::save);
        handlerA = new CollectHandler();
        handlerB = new CollectHandler();
        sessionA = connect(handlerA);
        sessionB = connect(handlerB);
        awaitOnline(2);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (sessionA != null && sessionA.isOpen()) sessionA.close(CloseStatus.NORMAL);
        if (sessionB != null && sessionB.isOpen()) sessionB.close(CloseStatus.NORMAL);
    }

    @Test
    @DisplayName("客户端发送 ping，服务端回复 pong（ping 不广播）")
    void pingPong() throws Exception {
        sessionA.sendMessage(new TextMessage("{\"type\":\"ping\"}"));
        String reply = handlerA.messages.poll(5, TimeUnit.SECONDS);
        assertThat(reply).isNotNull().contains("\"type\":\"pong\"");
        // ping 只回给发送方，B 不应收到任何消息
        assertThat(handlerB.messages.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("Alert 写操作后全部在线连接收到对应 LiveEvent（轻量摘要，不含 timeline）")
    void mutationBroadcastsToAllSessions() throws Exception {
        alertService.confirm("ALM-20260904-002", new ConfirmRequest("李娜"), null);
        String a1 = handlerA.messages.poll(5, TimeUnit.SECONDS);
        String b1 = handlerB.messages.poll(5, TimeUnit.SECONDS);
        assertThat(a1).contains("alert.changed").contains("ALM-20260904-002");
        assertThat(b1).contains("alert.changed");
        assertThat(a1).doesNotContain("timeline").doesNotContain("evidence");

        alertService.assign("ALM-20260904-002",
                new AssignRequest("安全员 王建国", null, null, "普通", 15, null, null, null), null);
        String a2 = handlerA.messages.poll(5, TimeUnit.SECONDS);
        assertThat(a2).contains("alert.assigned").contains("PENDING");
    }

    @Test
    @DisplayName("连接关闭后注册表清理，不再向其广播")
    void disconnectCleanup() throws Exception {
        sessionA.close(CloseStatus.NORMAL);
        awaitOnline(1);
        alertService.confirm("ALM-20260904-003", new ConfirmRequest("李娜"), null);
        String b = handlerB.messages.poll(5, TimeUnit.SECONDS);
        assertThat(b).contains("alert.changed");
        assertThat(handlerA.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    private void awaitOnline(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && registry.onlineCount() != expected) {
            Thread.sleep(50);
        }
        assertThat(registry.onlineCount()).isEqualTo(expected);
    }
}
