package com.bproject.ehm.platform.audit.application;

import com.bproject.ehm.assistant.adapter.in.web.AssistantController;
import com.bproject.ehm.assistant.application.AssistantApplicationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AssistantAuditAspectTest {
    @AfterEach void clean(){RequestContextHolder.resetRequestAttributes();}
    private AssistantController proxy(AssistantApplicationService service,EhmAuditRelay relay,String path) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("POST",path)));
        var factory=new AspectJProxyFactory(new AssistantController(service));factory.addAspect(new AssistantAuditAspect(relay));return factory.getProxy();
    }
    @Test void falseConnectivityResultIsFailureDespiteHttp200(){
        var service=mock(AssistantApplicationService.class);var relay=mock(EhmAuditRelay.class);
        when(service.test()).thenReturn(Map.of("success",false,"message","private provider error"));
        assertEquals(false,proxy(service,relay,"/api/ehm/v1/assistant/test").test().get("success"));
        verify(relay).record(any(),eq("FAILED"),eq("REQUEST"),anyLong());
    }
    @Test void modelFallbackIsNotModelSuccess(){
        var service=mock(AssistantApplicationService.class);var relay=mock(EhmAuditRelay.class);
        when(service.answer(any(),any(),any())).thenReturn(new AssistantApplicationService.AssistantResponse("private answer","local-fallback",Instant.now(),"S1",List.of(),"warning","model",0,0,"","lab"));
        proxy(service,relay,"/api/ehm/v1/assistant/chat").chat(new AssistantController.ChatRequest("private prompt","S1",null));
        verify(relay).record(any(),eq("FAILED"),eq("REQUEST"),anyLong());
    }
    @Test void providerSuccessIsRecordedOnce(){
        var service=mock(AssistantApplicationService.class);var relay=mock(EhmAuditRelay.class);when(service.test()).thenReturn(Map.of("success",true));
        proxy(service,relay,"/api/ehm/v1/assistant/test").test();
        verify(relay,times(1)).record(any(),eq("SUCCESS"),eq("REQUEST"),anyLong());
        assertEquals(true,((ServletRequestAttributes)RequestContextHolder.getRequestAttributes()).getRequest().getAttribute(EhmAuditRelay.BUSINESS_RECORDED));
    }
}
