package com.bproject.ehm.assistant.adapter.out.ai;

import com.bproject.ehm.assistant.application.*;
import com.bproject.ehm.assistant.ports.AssistantModelPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssistantModelRegistryTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private OpenAiCompatibleAssistantAdapter legacy() {
        return new OpenAiCompatibleAssistantAdapter(mapper,true,"https://api.deepseek.com","legacy-test-key","","deepseek-chat","anonymized",10);
    }
    @Test void legacyDeepseekRetainedWhileUnconfiguredLaboratoryIsVisibleButNotReady() throws Exception {
        var old=legacy();var registry=new AssistantModelRegistry(mapper,new MockEnvironment(),old);
        assertSame(old,registry.select("deepseek"));assertSame(old,registry.select(null));
        assertEquals(2,registry.catalog().size());assertFalse(registry.select("lab-qwen").status().ready());
        String json=mapper.writeValueAsString(registry.catalog());
        assertFalse(json.contains("legacy-test-key"));assertFalse(json.contains("api-key-file"));assertFalse(json.contains("http://"));
        assertThrows(ResponseStatusException.class,()->registry.select("http://malicious.example"));
    }
    @Test void oldLaboratoryProfileDoesNotBecomeDeepseekAndUnknownDefaultFailsClosed() {
        var lab=new OpenAiCompatibleAssistantAdapter(mapper,true,"https://laboratory.example/v1","unit-test-key","","Qwen3.8-27B","anonymized",10);
        var env=new MockEnvironment().withProperty("ehm.ai.base-url","https://laboratory.example/v1");
        var registry=new AssistantModelRegistry(mapper,env,lab);
        assertSame(lab,registry.select("lab-qwen"));assertSame(lab,registry.select(null));
        assertFalse(registry.select("deepseek").status().ready());
        env.withProperty("ehm.ai.default-model","arbitrary");
        assertThrows(IllegalArgumentException.class,()->new AssistantModelRegistry(mapper,env,lab));
    }
    @Test void environmentVariableMappingIsCompatibleWithServerEnvironmentFiles() {
        var env=new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource("fixture",Map.of(
            "EHM_AI_LAB_ENABLED","true","EHM_AI_LAB_API_KEY","test-lab-key","EHM_AI_LAB_DATA_POLICY","anonymized",
            "EHM_AI_LAB_BASE_URL","http://192.0.2.125:18047/v1",
            "EHM_AI_LAB_TRUSTED_HTTP_ORIGIN","http://192.0.2.125:18047","EHM_AI_DEFAULT_MODEL","lab-qwen")));
        var registry=new AssistantModelRegistry(mapper,env,legacy());
        assertEquals("lab-qwen",registry.defaultModelId());assertTrue(registry.select(null).status().ready());
    }
    @Test void selectedProviderReceivesOnlyItsKeyAndItsOwnNewConversation() throws Exception {
        List<String> bodies=new ArrayList<>(),keys=new ArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",ex->{
            bodies.add(new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));keys.add(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] data="{\"choices\":[{\"message\":{\"content\":\"分析完成[S1]\"}}]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200,data.length);ex.getResponseBody().write(data);ex.close();
        });server.start();
        try {
            var env=new MockEnvironment().withProperty("ehm.ai.default-model","lab-qwen");
            for(String p:List.of("lab","deepseek")){
                env.withProperty("ehm.ai."+p+".enabled","true").withProperty("ehm.ai."+p+".base-url","http://127.0.0.1:"+server.getAddress().getPort()+"/v1")
                    .withProperty("ehm.ai."+p+".api-key","test-"+p+"-key").withProperty("ehm.ai."+p+".data-policy","anonymized");
            }
            var registry=new AssistantModelRegistry(mapper,env,legacy());
            var tools=mock(AssistantDataTools.class);when(tools.devices()).thenReturn(List.of());
            when(tools.query(anyString(),anyString(),any(),anyInt())).thenReturn(new AssistantDataTools.QueryResult("{}",Map.of("records",List.of()),null));
            var service=new AssistantApplicationService(tools,registry,mapper);
            var qwen=service.answer("仅千问可见的历史甲",null,null,"lab-qwen");
            assertEquals("实验室千问",qwen.provider());assertEquals("model",qwen.mode());
            assertEquals(409,assertThrows(ResponseStatusException.class,()->service.answer("切换",qwen.sessionId(),null,"deepseek")).getStatusCode().value());
            assertEquals(1,bodies.size(),"Rejected switch must not issue a network call");
            var deepseek=service.answer("另一个新会话乙",null,null,"deepseek");
            assertEquals("DeepSeek",deepseek.provider());assertNotEquals(qwen.sessionId(),deepseek.sessionId());
            assertEquals(List.of("Bearer test-lab-key","Bearer test-deepseek-key"),keys);
            assertFalse(bodies.get(1).contains("仅千问可见的历史甲"));
            assertEquals("Qwen3.8-27B",mapper.readTree(bodies.get(0)).path("model").asText());
            assertTrue(mapper.readTree(bodies.get(0)).path("chat_template_kwargs").has("enable_thinking"));
            assertEquals("deepseek-chat",mapper.readTree(bodies.get(1)).path("model").asText());
            service.answer("继续原会话",qwen.sessionId(),null,null);
            assertEquals("Bearer test-lab-key",keys.get(2));
        } finally {server.stop(0);}
    }
}
