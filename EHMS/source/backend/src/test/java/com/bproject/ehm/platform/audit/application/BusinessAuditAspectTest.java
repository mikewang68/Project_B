package com.bproject.ehm.platform.audit.application;
import com.bproject.ehm.asset.adapter.in.web.AuditCommandProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class BusinessAuditAspectTest {
    @Test void explicitHttpRejectionRollsBackAndIsNotSuccess() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        var db=new JdbcTemplate(ds);db.execute("CREATE TABLE audit_probe_business(id VARCHAR(32))");var relay=mock(EhmAuditRelay.class);
        var request=new MockHttpServletRequest("POST","/api/ehm/v1/devices");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var proxy=new AspectJProxyFactory(new AuditCommandProbe(db));proxy.addAspect(new BusinessAuditAspect(relay,new TransactionTemplate(new DataSourceTransactionManager(ds))));
        AuditCommandProbe target=proxy.getProxy();assertEquals(400,target.reject().getStatusCode().value());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM audit_probe_business",Integer.class));
        verifyNoInteractions(relay);assertNull(request.getAttribute(EhmAuditRelay.BUSINESS_RECORDED));
    }
    @AfterEach void clean(){RequestContextHolder.resetRequestAttributes();}
    @Test void auditFailureRollsBackBusinessCommand(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        var db=new JdbcTemplate(ds);db.execute("CREATE TABLE audit_probe_business(id VARCHAR(32))");
        var relay=mock(EhmAuditRelay.class);doThrow(new IllegalStateException("audit unavailable")).when(relay).record(any(),eq("SUCCESS"),eq("BUSINESS"),anyLong());
        var request=new MockHttpServletRequest("POST","/api/ehm/v1/devices");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var proxy=new AspectJProxyFactory(new AuditCommandProbe(db));proxy.addAspect(new BusinessAuditAspect(relay,new TransactionTemplate(new DataSourceTransactionManager(ds))));
        AuditCommandProbe target=proxy.getProxy();assertThrows(IllegalStateException.class,target::create);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM audit_probe_business",Integer.class));
        assertNull(request.getAttribute(EhmAuditRelay.BUSINESS_RECORDED));
    }
    @Test void successfulCommandRecordsAuditBeforeCommit(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        var db=new JdbcTemplate(ds);db.execute("CREATE TABLE audit_probe_business(id VARCHAR(32))");var relay=mock(EhmAuditRelay.class);
        doAnswer(call->{assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return null;}).when(relay).record(any(),eq("SUCCESS"),eq("BUSINESS"),anyLong());
        var request=new MockHttpServletRequest("POST","/api/ehm/v1/devices");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var proxy=new AspectJProxyFactory(new AuditCommandProbe(db));proxy.addAspect(new BusinessAuditAspect(relay,new TransactionTemplate(new DataSourceTransactionManager(ds))));
        AuditCommandProbe target=proxy.getProxy();assertEquals("created",target.create());assertEquals(Boolean.TRUE,request.getAttribute(EhmAuditRelay.BUSINESS_RECORDED));
    }
}
