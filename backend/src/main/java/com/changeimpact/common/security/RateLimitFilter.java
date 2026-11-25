package com.changeimpact.common.security;

import com.changeimpact.auth.security.UserPrincipal;
import com.changeimpact.common.config.AppProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiting (Bucket4j): authentication endpoints per client IP, webhooks per client IP, the API per
 * authenticated user (per IP when anonymous). Exceeding a limit answers 429 with {@code Retry-After}.
 * Buckets are in-process; behind a proxy, client IPs come from X-Forwarded-For only with
 * {@code server.forward-headers-strategy} enabled (prod profile).
 *
 * <p>Registered inside the Spring Security chain (after JWT authentication), not as a servlet filter.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final AppProperties appProperties;
    private final Map<String, Entry> buckets = new ConcurrentHashMap<>();

    private record Entry(Bucket bucket, long[] lastUse) {}

    public RateLimitFilter(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !appProperties.getSecurity().getRateLimit().isEnabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AppProperties.Security.RateLimit cfg = appProperties.getSecurity().getRateLimit();
        String path = request.getRequestURI();
        String key;
        int perMinute;
        if (path.startsWith("/api/v1/auth/")) {
            key = "auth:" + request.getRemoteAddr();
            perMinute = cfg.getAuthRequestsPerMinute();
        } else if (path.startsWith("/api/v1/webhooks/")) {
            key = "hook:" + request.getRemoteAddr();
            perMinute = cfg.getWebhookRequestsPerMinute();
        } else {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            key = auth != null && auth.getPrincipal() instanceof UserPrincipal u ? "user:" + u.getId() : "anon:" + request.getRemoteAddr();
            perMinute = cfg.getApiRequestsPerMinute();
        }
        Entry entry = buckets.computeIfAbsent(key + "|" + perMinute, k -> new Entry(Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)).build())
                .build(), new long[1]));
        entry.lastUse()[0] = System.currentTimeMillis();
        ConsumptionProbe probe = entry.bucket().tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", Long.toString(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(1, (long) Math.ceil(probe.getNanosToWaitForRefill() / 1e9));
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"message\":\"Too many requests; retry in " + retryAfter + " s\"}");
    }

    /** Drops buckets idle for more than 10 minutes (they would be full again anyway). */
    @Scheduled(fixedDelay = 600_000)
    public void evictIdle() {
        long cutoff = System.currentTimeMillis() - 600_000;
        buckets.values().removeIf(e -> e.lastUse()[0] < cutoff);
    }
}
