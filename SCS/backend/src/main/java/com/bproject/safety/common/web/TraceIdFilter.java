package com.bproject.safety.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * TraceId 过滤器：
 * <ol>
 *   <li>客户端传入 X-Trace-Id 时沿用（限制长度，防止脏值），否则生成 UUID；</li>
 *   <li>写入 SLF4J MDC（logback pattern 通过 %X{traceId} 输出）；</li>
 *   <li>响应头 X-Trace-Id 原样回传，错误体中的 traceId 与之保持一致；</li>
 *   <li>输出一行访问日志：method/path/status/durationMs。</li>
 * </ol>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    private static final Logger accessLog = LoggerFactory.getLogger("ACCESS");
    private static final int MAX_TRACE_ID_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId = isValid(incoming) ? incoming : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            accessLog.info("method={} path={} status={} durationMs={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), duration);
            MDC.remove(MDC_KEY);
        }
    }

    private boolean isValid(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TRACE_ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!safe) {
                return false;
            }
        }
        return true;
    }
}
