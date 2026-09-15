package com.bdemo.energy.agent;

import com.bdemo.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CloudLlmClientTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void configuredModelReceivesFactsAndQuestion() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"测试模型回答\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            var client = client(server.getAddress().getPort());
            assertEquals("测试模型回答",client.answer("有多少告警？", Map.of("alerts",3)));
            var body = json.readTree(request.get());
            assertEquals("test-model",body.path("model").asText());
            assertTrue(body.path("messages").get(0).path("content").asText().contains("\"alerts\":3"));
            assertEquals("有多少告警？",body.path("messages").get(1).path("content").asText());
            assertEquals("Bearer test-only-key",authorization.get());
        } finally {server.stop(0);}
    }

    @Test void providerErrorsDoNotExposeResponseOrCredentials() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body="secret-provider-detail".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var error=assertThrows(BusinessException.class,()->client(server.getAddress().getPort()).answer("test",Map.of()));
            assertTrue(error.getMessage().contains("401"));
            assertFalse(error.getMessage().contains("secret-provider-detail"));
            assertFalse(error.getMessage().contains("test-only-key"));
        } finally {server.stop(0);}
    }

    @Test void enabledConfigRequiresCredentialsAndModel() {
        assertThrows(IllegalArgumentException.class,()->new CloudLlmClient(
                new AgentProperties(true,"https://example.invalid/v1","","",60,2048,.2),json));
        assertFalse(new CloudLlmClient(new AgentProperties(false,"","","",60,2048,.2),json).enabled());
    }
    private CloudLlmClient client(int port) {
        return new CloudLlmClient(new AgentProperties(true,"http://127.0.0.1:"+port+"/v1/","test-only-key","test-model",5,128,.2),json);
    }
}
