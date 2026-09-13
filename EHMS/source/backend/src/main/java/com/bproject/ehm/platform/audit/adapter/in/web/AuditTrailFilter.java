package com.bproject.ehm.platform.audit.adapter.in.web;

import com.bproject.ehm.platform.audit.application.AuditApplicationService;
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
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class AuditTrailFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AuditTrailFilter.class);
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final AuditApplicationService audit;

    public AuditTrailFilter(AuditApplicationService audit) {
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/ehm/v1/")
                || !MUTATING_METHODS.contains(request.getMethod().toUpperCase());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            try {
                String traceId = response.getHeader(TraceIdFilter.HEADER_NAME);
                audit.record(request.getHeader("X-EHM-Operator"), request.getHeader("X-EHM-Role"),
                        request.getMethod(), request.getRequestURI(), response.getStatus(), traceId,
                        clientAddress(request), (System.nanoTime() - started) / 1_000_000L);
            } catch (RuntimeException exception) {
                log.error("写入接口操作审计失败：{} {}", request.getMethod(), request.getRequestURI(), exception);
            }
        }
    }

    private String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
