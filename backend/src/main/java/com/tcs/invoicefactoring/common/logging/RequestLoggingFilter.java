package com.tcs.invoicefactoring.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns a correlation ID to every request and propagates it via MDC.
 * The ID is taken from the X-Correlation-ID header if present; otherwise generated.
 * The ID is always returned in the response header.
 *
 * IMPORTANT: This filter does NOT log request bodies or Authorization headers
 * to prevent accidental credential logging.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    public static final String CORRELATION_HEADER = "X-Correlation-ID";
    public static final String MDC_CORRELATION_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String correlationId = request.getHeader(CORRELATION_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_CORRELATION_KEY, correlationId);
        response.setHeader(CORRELATION_HEADER, correlationId);

        long start = System.currentTimeMillis();
        try {
            log.debug("→ {} {} [correlationId={}]",
                    request.getMethod(), request.getRequestURI(), correlationId);
            filterChain.doFilter(request, response);
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            log.debug("← {} {} {} {}ms [correlationId={}]",
                    request.getMethod(), request.getRequestURI(),
                    response.getStatus(), elapsed, correlationId);
            // Always clear MDC to prevent context leakage between requests
            MDC.clear();
        }
    }
}
