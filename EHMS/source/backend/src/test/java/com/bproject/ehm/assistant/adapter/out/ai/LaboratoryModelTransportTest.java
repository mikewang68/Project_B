package com.bproject.ehm.assistant.adapter.out.ai;

import com.bproject.ehm.assistant.ports.AssistantModelPort.ProviderException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class LaboratoryModelTransportTest {
    private OpenAiCompatibleAssistantAdapter adapter(String url, String origin, String model) {
        return new OpenAiCompatibleAssistantAdapter(new ObjectMapper(), true, url, "unit-test-key", "",
                model, "anonymized", 10, "实验室千问", origin, false);
    }
    @Test void onlyExplicitHttpHostAndPortAreTrusted() {
        assertFalse(adapter("http://192.0.2.125:18047/v1", "", "Qwen/Qwen3-test").status().ready());
        assertTrue(adapter("http://192.0.2.125:18047/v1", "http://192.0.2.125:18047", "Qwen/Qwen3-test").status().ready());
        assertFalse(adapter("http://192.0.2.125:18048/v1", "http://192.0.2.125:18047", "Qwen/Qwen3-test").status().ready());
        assertFalse(adapter("http://192.0.2.126:18047/v1", "http://192.0.2.125:18047", "Qwen/Qwen3-test").status().ready());
        assertFalse(adapter("http://user:secret@192.0.2.125:18047/v1", "http://192.0.2.125:18047", "Qwen/Qwen3-test").status().ready());
        assertFalse(adapter("http://192.0.2.125:18047/v1?api_key=secret", "http://192.0.2.125:18047", "Qwen/Qwen3-test").status().ready());
        assertFalse(adapter("ftp://192.0.2.125:18047/v1", "http://192.0.2.125:18047", "Qwen/Qwen3-test").status().ready());
    }
    @Test void blankModelCannotBeMistakenForConfiguredQwen() {
        var status=adapter("https://lab-model.example/v1", "", "").status();
        assertFalse(status.ready());assertTrue(status.message().contains("模型ID"));
        assertEquals("实验室千问",status.provider());assertFalse(status.toString().contains("unit-test-key"));
    }
    @Test void laboratoryPayloadUsesExactModelAndNoDeepSeekOrToolsParameters() throws Exception {
        var body=new AtomicReference<String>();var auth=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",ex->{
            body.set(new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes="{\"choices\":[{\"message\":{\"content\":\"建议复测[S1]\",\"reasoning_content\":\"private reasoning\"}}],\"usage\":{\"prompt_tokens\":18,\"completion_tokens\":6}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200,bytes.length);ex.getResponseBody().write(bytes);ex.close();
        });server.start();
        try {
            var adapter=adapter("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/", "", "Qwen/Qwen3-test");
            var result=adapter.complete(List.of(Map.of("role","user","content","查询DEV_001")),List.of(Map.of("type","function")));
            var payload=new ObjectMapper().readTree(body.get());
            assertEquals("Qwen/Qwen3-test",payload.path("model").asText());assertEquals("Bearer unit-test-key",auth.get());
            assertFalse(payload.has("tools"));assertFalse(payload.has("tool_choice"));assertFalse(payload.has("thinking"));
            assertEquals("建议复测[S1]",result.content());assertEquals(18,result.promptTokens());
            assertFalse(result.assistantMessage().containsKey("reasoning_content"));assertFalse(adapter.supportsToolCalls());
        } finally {server.stop(0);}
    }
    @Test void providerRedirectCannotSendKeyToAnotherEndpoint() throws Exception {
        var hits=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",ex->{ex.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/other");ex.sendResponseHeaders(302,-1);ex.close();});
        server.createContext("/other",ex->{hits.incrementAndGet();ex.sendResponseHeaders(200,-1);ex.close();});server.start();
        try {
            var adapter=adapter("http://127.0.0.1:"+server.getAddress().getPort()+"/v1", "", "Qwen/Qwen3-test");
            var error=assertThrows(ProviderException.class,()->adapter.complete(List.of(Map.of("role","user","content","测试")),List.of()));
            assertEquals("PROVIDER_HTTP_302",error.code());assertEquals(0,hits.get());assertFalse(error.getMessage().contains("unit-test-key"));
        } finally {server.stop(0);}
    }
}
