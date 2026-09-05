package com.bproject.safety.infrastructure.timeseries;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OpenGeminiClientTest {
    static HttpServer server;
    static int port;

    @BeforeAll
    static void startMockServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/ping", exchange -> {
            int code = "fail".equals(exchange.getRequestURI().getQuery()) ? 500 : 204;
            exchange.getResponseHeaders().add("X-Geminidb-Version", "1.5.2-mock");
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stopMockServer() {
        server.stop(0);
    }

    @Test
    void ping204IsHealthy() {
        OpenGeminiClient client = new OpenGeminiClient(
                new OpenGeminiProperties(true, "http://127.0.0.1:" + port, 2000));
        OpenGeminiClient.PingResult result = client.ping();
        assertThat(result.ok()).isTrue();
        assertThat(result.version()).isEqualTo("1.5.2-mock");
    }

    @Test
    void ping500IsUnhealthy() throws IOException {
        HttpServer failing = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int failPort = failing.getAddress().getPort();
        failing.createContext("/ping", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        failing.start();
        try {
            OpenGeminiClient client = new OpenGeminiClient(
                    new OpenGeminiProperties(true, "http://127.0.0.1:" + failPort, 2000));
            assertThat(client.ping().ok()).isFalse();
        } finally {
            failing.stop(0);
        }
    }

    @Test
    void unreachableHostIsUnhealthy() {
        OpenGeminiClient bad = new OpenGeminiClient(
                new OpenGeminiProperties(true, "http://127.0.0.1:1", 500));
        assertThat(bad.ping().ok()).isFalse();
    }

    @Test
    void disabledClientReturnsDisabledResult() {
        OpenGeminiClient client = new OpenGeminiClient(
                new OpenGeminiProperties(false, "", 2000));
        assertThat(client.isEnabled()).isFalse();
        assertThat(client.ping().ok()).isFalse();
        assertThat(client.ping().detail()).contains("未启用");
    }

    @Test
    void trailingSlashIsStripped() {
        OpenGeminiProperties props = new OpenGeminiProperties(true,
                "http://127.0.0.1:" + port + "/", 1000);
        assertThat(props.baseUrl()).doesNotEndWith("/");
    }
}
