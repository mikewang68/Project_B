package com.bproject.ehm.platform.audit.application;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditTaskAspectTest {
    @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
    MockHttpServletResponse response(){var r=new MockHttpServletResponse();RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(),r));return r;}
    AuditedTask annotation(){var a=mock(AuditedTask.class);when(a.action()).thenReturn("EXECUTE");when(a.menu()).thenReturn("health");return a;}
    @Test void committedResultIsNotReplacedWhenFinalizationIsUnavailable() throws Throwable {
        var response=response();var tasks=mock(EhmAuditedTasks.class);when(tasks.start(any(),any(),any())).thenReturn("TASK-1");when(tasks.finish("TASK-1","SUCCESS")).thenThrow(new IllegalStateException());
        var call=mock(ProceedingJoinPoint.class);when(call.proceed()).thenReturn("real-result");
        assertEquals("real-result",new AuditTaskAspect(tasks).around(call,annotation()));assertEquals("RECONCILIATION_REQUIRED",response.getHeader("X-EHM-Audit-Task-State"));
    }
    @Test void explicit403IsNotTaskSuccess() throws Throwable {
        response();var tasks=mock(EhmAuditedTasks.class);when(tasks.start(any(),any(),any())).thenReturn("TASK-1");when(tasks.finish(any(),any())).thenReturn(true);
        var call=mock(ProceedingJoinPoint.class);when(call.proceed()).thenReturn(ResponseEntity.status(403).build());new AuditTaskAspect(tasks).around(call,annotation());verify(tasks).finish("TASK-1","DENIED");
    }
    @Test void exceptionIsPreservedAndFailedExecutionIsAudited() throws Throwable {
        response();var tasks=mock(EhmAuditedTasks.class);when(tasks.start(any(),any(),any())).thenReturn("TASK-1");when(tasks.finish(any(),any())).thenReturn(true);
        var call=mock(ProceedingJoinPoint.class);var denial=new ResponseStatusException(HttpStatus.FORBIDDEN);when(call.proceed()).thenThrow(denial);
        assertSame(denial,assertThrows(ResponseStatusException.class,()->new AuditTaskAspect(tasks).around(call,annotation())));verify(tasks).finish("TASK-1","DENIED");
    }
}
