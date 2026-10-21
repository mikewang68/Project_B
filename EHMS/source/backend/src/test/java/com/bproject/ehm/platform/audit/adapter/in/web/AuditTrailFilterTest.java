package com.bproject.ehm.platform.audit.adapter.in.web;
import com.bproject.ehm.platform.audit.application.AuditApplicationService;
import com.bproject.ehm.platform.audit.application.EhmAuditRelay;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class AuditTrailFilterTest {
    @Test void legacyFailureDoesNotBlockPublicQueue() throws Exception {
        var audit=mock(AuditApplicationService.class); ObjectProvider<EhmAuditRelay> provider=mock(ObjectProvider.class);
        var relay=mock(EhmAuditRelay.class);when(provider.getIfAvailable()).thenReturn(relay);
        doThrow(new IllegalStateException("legacy offline")).when(audit).record(any(),any(),any(),any(),anyInt(),any(),any(),anyLong());
        var request=new MockHttpServletRequest("GET","/api/ehm/v1/devices");
        new AuditTrailFilter(audit,provider).doFilter(request,new MockHttpServletResponse(),(a,b)->{});
        verify(relay).record(eq(request),eq("SUCCESS"),eq("REQUEST"),anyLong());
    }
    @Test void deniedRequestIsNotSuccessAndDoesNotTrustForgedActor() throws Exception {
        var audit=mock(AuditApplicationService.class);ObjectProvider<EhmAuditRelay> provider=mock(ObjectProvider.class);
        var relay=mock(EhmAuditRelay.class);when(provider.getIfAvailable()).thenReturn(relay);
        var request=new MockHttpServletRequest("DELETE","/api/ehm/v1/devices/D1");request.addHeader("X-EHM-Operator","admin");
        new AuditTrailFilter(audit,provider).doFilter(request,new MockHttpServletResponse(),(a,b)->((jakarta.servlet.http.HttpServletResponse)b).setStatus(403));
        verify(relay).record(eq(request),eq("DENIED"),eq("REQUEST"),anyLong());
        verify(audit).record(eq("anonymous"),any(),any(),eq("/api/ehm/v1/devices/{unresolved}"),eq(403),any(),any(),anyLong());
    }
    @Test void committedBusinessSuccessIsNotDuplicated() throws Exception {
        var audit=mock(AuditApplicationService.class);ObjectProvider<EhmAuditRelay> provider=mock(ObjectProvider.class);
        var relay=mock(EhmAuditRelay.class);when(provider.getIfAvailable()).thenReturn(relay);
        var request=new MockHttpServletRequest("POST","/api/ehm/v1/devices");
        request.setAttribute(EhmAuditRelay.BUSINESS_RECORDED,true);
        new AuditTrailFilter(audit,provider).doFilter(request,new MockHttpServletResponse(),(a,b)->{});
        verify(relay,never()).record(any(),any(),any(),anyLong());
    }
    @Test void queryIsRecordedAndBrowserActorHeadersIgnored() throws Exception {
        var audit=mock(AuditApplicationService.class);ObjectProvider<EhmAuditRelay> provider=mock(ObjectProvider.class);
        var filter=new AuditTrailFilter(audit,provider);var request=new MockHttpServletRequest("GET","/api/ehm/v1/devices");
        request.addHeader("X-EHM-Operator","forged-admin");request.addHeader("X-EHM-Role","system-admin");
        filter.doFilter(request,new MockHttpServletResponse(),(a,b)->{});
        verify(audit).record(eq("anonymous"),eq("未认证身份"),eq("GET"),eq("/api/ehm/v1/devices"),eq(200),anyString(),any(),anyLong());
    }
    @Test void thrownFailureNotMisreportedAsHttp200() throws Exception {
        var audit=mock(AuditApplicationService.class);ObjectProvider<EhmAuditRelay> provider=mock(ObjectProvider.class);
        var filter=new AuditTrailFilter(audit,provider);var request=new MockHttpServletRequest("POST","/api/ehm/v1/devices");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->filter.doFilter(request,new MockHttpServletResponse(),(a,b)->{throw new IllegalStateException("failure");}));
        verify(audit).record(any(),any(),eq("POST"),any(),eq(500),any(),any(),anyLong());
    }
}
