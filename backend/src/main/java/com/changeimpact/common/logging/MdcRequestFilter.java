package com.changeimpact.common.logging;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that injects a unique request ID and remote IP into the
 * MDC (Mapped Diagnostic Context) for every incoming HTTP request.
 *
 * <p>This enables log correlation: every log line produced within a request
 * includes the request ID, making it trivial to trace all activity for a
 * single request in log aggregation tools like Grafana Loki, ELK, or CloudWatch.
 *
 * <p>The request ID is also added to the response header {@code X-Request-Id}
 * so that clients and load balancers can correlate their traces.
 */
public class MdcRequestFilter implements Filter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_REQUEST_ID = "requestId";
    private static final String MDC_REMOTE_IP = "remoteIp";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;

        String requestId = httpRequest.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_REMOTE_IP, getClientIp(httpRequest));

        try {
            if (response instanceof jakarta.servlet.http.HttpServletResponse httpResponse) {
                httpResponse.setHeader(REQUEST_ID_HEADER, requestId);
            }
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // Take only the first IP (client IP; subsequent are proxies)
            return forwardedFor.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}

