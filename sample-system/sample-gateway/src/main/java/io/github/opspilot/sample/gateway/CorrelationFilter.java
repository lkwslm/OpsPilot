package io.github.opspilot.sample.gateway;

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
final class CorrelationFilter extends OncePerRequestFilter {
    static final String REQUEST_ID = "X-Request-Id";
    static final String TRACE_ID = "X-Trace-Id";
    static final String RUN_ID = "X-Run-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = valueOrGenerated(request.getHeader(REQUEST_ID));
        String traceId = valueOrGenerated(request.getHeader(TRACE_ID));
        String runId = safe(request.getHeader(RUN_ID));
        try (var ignoredRequest = MDC.putCloseable("requestId", requestId);
             var ignoredTrace = MDC.putCloseable("traceId", traceId);
             var ignoredRun = MDC.putCloseable("runId", runId)) {
            response.setHeader(REQUEST_ID, requestId);
            response.setHeader(TRACE_ID, traceId);
            if (!runId.isBlank()) response.setHeader(RUN_ID, runId);
            chain.doFilter(request, response);
        }
    }

    private static String valueOrGenerated(String value) {
        String safe = safe(value);
        return safe.isBlank() ? UUID.randomUUID().toString() : safe;
    }

    private static String safe(String value) {
        if (value == null || value.length() > 128 || !value.matches("[A-Za-z0-9._:-]*")) return "";
        return value;
    }
}
