package com.bproject.ehm.platform.audit.adapter.in.web;

import com.bproject.ehm.platform.audit.application.AuditApplicationService;
import com.bproject.ehm.platform.audit.application.EhmAuditRelay;
import org.springframework.beans.factory.ObjectProvider;
import com.bproject.ehm.shared.web.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuditTrailFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AuditTrailFilter.class);
    private final AuditApplicationService audit;
    private final ObjectProvider<EhmAuditRelay> relay;

    public AuditTrailFilter(AuditApplicationService audit, ObjectProvider<EhmAuditRelay> relay) {
        this.audit = audit;
        this.relay = relay;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/ehm/v1/")
                || Set.of("OPTIONS","HEAD").contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        boolean failed = false;
        if (request.getAttribute("auditTraceId") == null)
            request.setAttribute("auditTraceId", java.util.UUID.randomUUID().toString());
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            failed = true;
            throw exception;
        } finally {
            String traceId = response.getHeader(TraceIdFilter.HEADER_NAME);
            if (traceId == null) traceId = (String) request.getAttribute("auditTraceId");
            var principal = IamAuditIdentityFilter.actor(request);
            int status = failed && response.getStatus() < 400 ? 500 : response.getStatus();
            try {
                audit.record(principal == null ? "anonymous" : principal.getName(), principal == null ? "未认证身份" : "已认证会话",
                        request.getMethod(), com.bproject.ehm.platform.audit.application.AuditRequestMetadata.route(request), status, traceId,
                        clientAddress(request), (System.nanoTime() - started) / 1_000_000L);
            } catch (RuntimeException exception) {
                log.error("本地接口操作审计写入失败", exception);
            }
            // Independent sink: a legacy audit failure must not prevent durable public delivery.
            try {
                var target = relay.getIfAvailable();
                if (target != null && Boolean.TRUE.equals(request.getAttribute(EhmAuditRelay.BUSINESS_RECORDED)) && status >= 400)
                    request.setAttribute(EhmAuditRelay.EVENT_ID, request.getAttribute(EhmAuditRelay.EVENT_ID) + "-HTTP");
                if (target != null && (!Boolean.TRUE.equals(request.getAttribute(EhmAuditRelay.BUSINESS_RECORDED)) || status >= 400))
                    target.record(request, status == 401 || status == 403 ? "DENIED" : status < 400 ? "SUCCESS" : "FAILED", "REQUEST", (System.nanoTime()-started)/1_000_000L);
            } catch (RuntimeException exception) {
                log.error("公共审计入队失败；请检查audit-delivery诊断状态", exception);
            }
        }
    }

    private String clientAddress(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
