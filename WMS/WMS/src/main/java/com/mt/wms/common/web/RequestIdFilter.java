package com.mt.wms.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String requestId = supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied;
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("request_id", requestId)) {
            chain.doFilter(request, response);
        }
    }
}

