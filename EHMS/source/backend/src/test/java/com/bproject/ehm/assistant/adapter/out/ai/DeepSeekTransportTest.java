package com.bproject.ehm.assistant.adapter.out.ai;

import com.bproject.ehm.assistant.ports.AssistantModelPort.ProviderException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekTransportTest {
    @TempDir Path directory;
    @Test void sendsExpectedDeepSeekPayloadAndParsesToolsAndUsage() throws Exception {
        var body=new AtomicReference<String>();var auth=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",ex->{body.set(new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));auth.set(ex.getRequestHeaders().getFirst("Authorization"));byte[] bytes="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"query_ehm\",\"arguments\":\"{\\\"category\\\":\\\"devices\\\"}\"}}]}}],\"usage\":{\"prompt_tokens\":22,\"completion_tokens\":8}}".getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(200,bytes.length);ex.getResponseBody().write(bytes);ex.close();});server.start();
        try{var adapter=new OpenAiCompatibleAssistantAdapter(new ObjectMapper(),true,"http://127.0.0.1:"+server.getAddress().getPort()+"/v1","test-only-key","","deepseek-chat","anonymized",10);
            var result=adapter.complete(List.of(Map.of("role","user","content","你好")),List.of(Map.of("type","function")));
            assertEquals("Bearer test-only-key",auth.get());assertTrue(body.get().contains("deepseek-chat"));assertTrue(body.get().contains("tools"));assertEquals("query_ehm",result.toolCalls().get(0).name());assertEquals(22,result.promptTokens());assertFalse(adapter.status().toString().contains("test-only-key"));
        }finally{server.stop(0);}
    }
    @Test void authenticationFailureIsSafeAndKeyFileCanBeUpdatedWithoutRestart() throws Exception {
        Path key=directory.resolve("key");Files.writeString(key,"dummy-one");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var auth=new AtomicReference<String>();
        server.createContext("/chat/completions",ex->{auth.set(ex.getRequestHeaders().getFirst("Authorization"));ex.sendResponseHeaders(401,-1);ex.close();});server.start();
        try {var adapter=new OpenAiCompatibleAssistantAdapter(new ObjectMapper(),true,"http://127.0.0.1:"+server.getAddress().getPort(),"",key.toString(),"deepseek-chat","anonymized",10);
            Files.writeString(key,"dummy-two");var error=assertThrows(ProviderException.class,()->adapter.complete(List.of(Map.of("role","user","content","测试")),List.of()));
            assertEquals("Bearer dummy-two",auth.get());assertEquals("PROVIDER_HTTP_401",error.code());assertFalse(error.getMessage().contains("dummy"));
        }finally{server.stop(0);}
    }
    @Test void unapprovedPolicyPreventsOutboundCallEvenWithKey() {
        var adapter=new OpenAiCompatibleAssistantAdapter(new ObjectMapper(),true,"https://api.deepseek.com/v1","dummy","","deepseek-chat","disabled",10);
        assertFalse(adapter.status().ready());assertThrows(ProviderException.class,()->adapter.complete(List.of(),List.of()));
    }
}
