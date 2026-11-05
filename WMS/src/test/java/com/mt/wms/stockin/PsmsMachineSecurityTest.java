package com.mt.wms.stockin;

import com.mt.wms.auth.*;
import com.mt.wms.auth.sso.WmsSsoExchangeService;
import com.mt.wms.common.config.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers={PsmsMachineController.class,PsmsInboundController.class})
@Import({SecurityConfiguration.class,PsmsMachineSecurityConfiguration.class,PsmsMachineSecurityTest.Config.class,PsmsMachineErrors.class})
class PsmsMachineSecurityTest {
    private static final String MACHINE="/api/v1/integrations/psms/inbound-tasks";
    private static final String HUMAN="/api/v1/stockin/psms-tasks/TASK/confirm";
    private static final String TOKEN="synthetic-inbound-token-for-tests-only-012345";
    private static final String BODY="""
        {"schemaVersion":"1.0","eventId":"EVENT","taskId":"TASK","version":1,"planId":"PLAN","workOrderId":"WORK",
         "companyCode":"C","ownerCode":"O","expectedArrivalAt":"2026-11-04T16:00:00+08:00",
         "lines":[{"lineId":"1","goodCode":"G","plannedQty":100,"unit":"捆"}]}
        """;
    @Autowired MockMvc mvc;
    @MockitoBean PsmsInboundService inbound;
    @MockitoBean AuthRepository authRepository;
    @MockitoBean WmsSsoExchangeService sso;
    @TestConfiguration static class Config {
        @Bean PsmsInboundSettings psmsSettings(){return new PsmsInboundSettings(true,TOKEN,"synthetic-outbound-token-for-tests-only-987654","C","O","PROJECT","REGION");}
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private MockHttpSession session(boolean readonly){
        var principal=new WmsPrincipal(7L,1L,"C","Company","u","User",List.of("MANAGER"),Set.of("stockin:write","stockin:read"),false,readonly);
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
        var session=new MockHttpSession();session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);
        when(authRepository.refreshPrincipal(any())).thenReturn(Optional.of(principal));return session;
    }
    @Test void validServiceIdentityAcceptsRawResponseWithoutCsrf()throws Exception{
        when(inbound.accept(any())).thenReturn(new PsmsInboundModels.Accepted("TASK",1,"WAITING_WAREHOUSE"));
        mvc.perform(post(MACHINE).header("Authorization","Bearer "+TOKEN).contentType("application/json").content(BODY))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.taskId").value("TASK")).andExpect(jsonPath("$.data").doesNotExist());
        verify(inbound).accept(any());verifyNoInteractions(authRepository,sso);
    }
    @Test void remotePeerAndForwardedProxiesAreDeniedEvenWithCorrectBearer()throws Exception{
        mvc.perform(post(MACHINE).with(req->{req.setRemoteAddr("198.51.100.74");return req;})
                .header("Authorization","Bearer "+TOKEN).contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        for(String header:List.of("Forwarded","X-Forwarded-For","X-Forwarded-Host","X-Real-IP"))
            mvc.perform(post(MACHINE).header(header,"127.0.0.1").header("Authorization","Bearer "+TOKEN)
                .contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(inbound);
    }
    @Test void browserAdministratorSessionCannotReplaceServiceCredential()throws Exception{
        mvc.perform(post(MACHINE).session(session(false)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(inbound);
    }
    @Test void wrongBearerIsDeniedBeforeReadingBusinessPayload()throws Exception{
        mvc.perform(post(MACHINE).header("Authorization","Bearer wrong").contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());verifyNoInteractions(inbound);
    }
    @Test void machineCredentialCannotOperateBrowserStockinEndpoints()throws Exception{
        mvc.perform(post(HUMAN).with(csrf()).header("Authorization","Bearer "+TOKEN).contentType("application/json").content("{\"warehouseCode\":\"W\",\"ownerCode\":\"O\"}"))
                .andExpect(status().isUnauthorized());verifyNoInteractions(inbound);
    }
    @Test void browserConfirmationStillRequiresCsrfAndWritableAccount()throws Exception{
        String confirm="{\"warehouseCode\":\"W\",\"ownerCode\":\"O\"}";
        mvc.perform(post(HUMAN).session(session(false)).contentType("application/json").content(confirm)).andExpect(status().isForbidden());
        mvc.perform(post(HUMAN).session(session(true)).with(csrf()).contentType("application/json").content(confirm)).andExpect(status().isForbidden());
        mvc.perform(post(HUMAN).session(session(false)).with(csrf()).contentType("application/json").content(confirm)).andExpect(status().isOk());
        verify(inbound,times(1)).confirm(eq("TASK"),any(),any(),any());
    }
    @Test void malformedPayloadReturns400AndDomainErrorRemains422()throws Exception{
        mvc.perform(post(MACHINE).header("Authorization","Bearer "+TOKEN).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PSMS_INVALID_REQUEST"));
        when(inbound.accept(any())).thenThrow(PsmsInboundException.invalid("UNIT_MISMATCH","unit mismatch"));
        mvc.perform(post(MACHINE).header("Authorization","Bearer "+TOKEN).contentType("application/json").content(BODY))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNIT_MISMATCH"));
    }
}
