package com.bproject.ehm.assistant.application;

import com.bproject.ehm.assistant.ports.AssistantModelPort;
import com.bproject.ehm.assistant.ports.AssistantModelPort.*;
import com.bproject.ehm.shared.error.ValidationException;
import com.bproject.ehm.asset.application.DeviceView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssistantApplicationServiceTest {
    private DeviceView device() { return new DeviceView("GT-01","机密场站门吊","门吊","甲方区域","运行",58,"高风险","severe",98.6,"已接入","振动异常","2026-10-10","张三",45.0,4.8,20.0,null,Instant.now(),1L); }
    private AssistantDataTools tools() {
        AssistantDataTools t=mock(AssistantDataTools.class);
        when(t.devices()).thenReturn(List.of(device())); when(t.definitions()).thenReturn(List.of(Map.of("type","function")));
        when(t.query(anyString(),anyString(),any(),anyInt())).thenAnswer(inv -> {
            int n=inv.getArgument(3); return new AssistantDataTools.QueryResult("{\"device\":\"DEV_001\",\"health\":58}",Map.of("records",List.of(Map.of("health",58))),new AssistantDataTools.Source("S"+n,"设备","fleet",1,Instant.now()));
        }); return t;
    }
    @Test void toolConversationUsesFreshDataAndKeepsDeviceForFollowup() {
        var tools=tools();var model=mock(AssistantModelPort.class);
        when(model.supportsToolCalls()).thenReturn(true);
        when(model.status()).thenReturn(new ModelStatus(true,true,true,"DeepSeek","deepseek-chat","anonymized","ready"));
        when(model.complete(anyList(),anyList())).thenReturn(
                new Completion("",List.of(new ToolCall("call1","query_ehm","{\"category\":\"calibrations\",\"device_code\":\"DEV_001\"}")),Map.of("role","assistant","tool_calls",List.of()),10,2),
                new Completion("DEV_001健康分58，建议复测[S1]",List.of(),Map.of(),12,8),
                new Completion("建议先核对校准记录[S1]",List.of(),Map.of(),15,10));
        var service=new AssistantApplicationService(tools,model,new ObjectMapper());
        var first=service.answer("GT-01有什么风险？",null,null);
        assertEquals("model",first.mode());assertEquals("DeepSeek",first.provider());assertTrue(first.answer().contains("GT-01"));assertEquals(3,first.sources().size());
        var second=service.answer("它需要先检查什么？",first.sessionId(),null);
        assertEquals(first.sessionId(),second.sessionId());assertEquals("GT-01",second.deviceCode());
        assertEquals(4,service.history(first.sessionId()).size());
        verify(model,atLeastOnce()).complete(argThat(messages -> !messages.toString().contains("机密场站") && !messages.toString().contains("张三") && !messages.toString().contains("GT-01")),anyList());
        service.clear(first.sessionId());assertTrue(service.history(first.sessionId()).isEmpty());
    }
    @Test void missingKeyAndProviderFailureAreExplicitRatherThanPretendAi() {
        var model=mock(AssistantModelPort.class);var service=new AssistantApplicationService(tools(),model,new ObjectMapper());
        when(model.status()).thenReturn(new ModelStatus(false,false,false,"DeepSeek","deepseek-chat","disabled","未配置密钥"));
        var local=service.answer("检查设备");assertEquals("local-data",local.mode());assertTrue(local.warning().contains("未配置"));verify(model,never()).complete(anyList(),anyList());
        when(model.status()).thenReturn(new ModelStatus(true,true,true,"DeepSeek","deepseek-chat","anonymized","ready"));
        when(model.complete(anyList(),anyList())).thenThrow(new ProviderException("PROVIDER_HTTP_402","余额不足"));
        var fallback=service.answer("检查设备");assertEquals("local-fallback",fallback.mode());assertTrue(fallback.warning().contains("余额不足"));
    }
    @Test void boundsUserInputAndPreventsUnlimitedToolLoops() {
        var model=mock(AssistantModelPort.class);var service=new AssistantApplicationService(tools(),model,new ObjectMapper());
        when(model.supportsToolCalls()).thenReturn(true);
        assertThrows(ValidationException.class,()->service.answer("a".repeat(2001)));
        when(model.status()).thenReturn(new ModelStatus(true,true,true,"DeepSeek","deepseek-chat","anonymized","ready"));
        when(model.complete(anyList(),anyList())).thenReturn(new Completion("",List.of(new ToolCall("c","query_ehm","{}")),Map.of("role","assistant"),1,1));
        assertEquals("local-fallback",service.answer("设备风险").mode());verify(model,times(3)).complete(anyList(),anyList());
    }
    @Test void laboratoryModelWithoutToolsStillUsesAnonymizedDatabaseContext() {
        var model=mock(AssistantModelPort.class);var tools=tools();
        when(model.status()).thenReturn(new ModelStatus(true,true,true,"实验室千问","Qwen/Qwen3-test","anonymized","ready"));
        when(model.supportsToolCalls()).thenReturn(false);
        when(model.complete(anyList(),eq(List.of()))).thenReturn(new Completion("DEV_001需要复测[S1]",List.of(),Map.of(),20,8));
        var result=new AssistantApplicationService(tools,model,new ObjectMapper()).answer("GT-01风险如何？");
        assertEquals("model",result.mode());assertEquals("实验室千问",result.provider());assertTrue(result.answer().contains("GT-01"));
        assertEquals(2,result.sources().size());
        verify(tools,never()).definitions();
        verify(model).complete(argThat(messages->messages.toString().contains("DEV_001")&&!messages.toString().contains("机密场站")&&!messages.toString().contains("GT-01")),eq(List.of()));
    }
    @Test void privacyMasksIdentityNetworkAndSecretsButRestoresDeviceLocally() {
        AssistantPrivacy p=new AssistantPrivacy();p.register(List.of(device()));
        String safe=p.outgoing("GT-01 机密场站门吊 甲方区域 张三 192.168.1.5 13812345678 x@y.com api_key=abc /data/config.secret sk-testsecret");
        for(String raw:List.of("GT-01","机密场站","甲方区域","张三","192.168","138123","x@y","abc","/data","sk-test"))assertFalse(safe.contains(raw),raw);
        assertEquals("GT-01",p.resolveCode("DEV_001"));assertEquals("GT-01检查",p.restore("DEV_001检查"));
    }
    @Test void databaseFailureStopsOutboundDataInsteadOfPretendingNoRecords() {
        var tools=tools();when(tools.devices()).thenThrow(new RuntimeException("db error"));
        var model=mock(AssistantModelPort.class);when(model.status()).thenReturn(new ModelStatus(true,true,true,"DeepSeek","deepseek-flash","anonymized","ready"));
        var result=new AssistantApplicationService(tools,model,new ObjectMapper()).answer("设备风险");
        assertEquals("local-fallback",result.mode());assertTrue(result.warning().contains("未发送"));assertTrue(result.sources().isEmpty());verify(model,never()).complete(anyList(),anyList());
    }
}
