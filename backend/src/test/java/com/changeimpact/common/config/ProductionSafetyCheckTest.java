package com.changeimpact.common.config;

import com.changeimpact.github.service.TokenEncryptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Production safety check")
class ProductionSafetyCheckTest {

    private static Map<String, String> safe() {
        Map<String, String> m = new HashMap<>();
        m.put("app.security.jwt.secret", "a-real-secret-that-is-long-enough-0123456789");
        m.put("TOKEN_ENCRYPTION_KEY", "q3pbN0bS9yK8m2xJ7qY1z4V6c5W8e0R3t2U1i9O8p7A=");
        m.put("spring.datasource.password", "s3cure-db");
        m.put("spring.rabbitmq.username", "ci");
        m.put("spring.rabbitmq.password", "r4bbit");
        m.put("app.security.bcrypt.strength", "12");
        m.put("app.security.allowed-origins[0]", "https://impact.example.com");
        return m;
    }

    @Test
    @DisplayName("a correct production configuration has no problems")
    void ok() {
        assertEquals(List.of(), ProductionSafetyCheck.problems(safe()::get, true));
    }

    @Test
    @DisplayName("published defaults and unset variables are reported by name, never by value")
    void problems() {
        Map<String, String> m = safe();
        m.put("app.security.jwt.secret", "changeimpact-jwt-secret-key-must-be-at-least-32-characters-long-for-hs256");
        m.put("TOKEN_ENCRYPTION_KEY", TokenEncryptionService.DEV_DEFAULT_KEY);
        m.put("spring.datasource.password", ProductionSafetyCheck.UNSET + "DB_PASSWORD");
        m.put("spring.rabbitmq.username", "guest");
        m.put("spring.rabbitmq.password", "guest");
        m.put("app.github.app-id", "123");
        m.put("app.ingestion.allow-local-repositories", "true");
        List<String> p = ProductionSafetyCheck.problems(m::get, true);
        assertEquals(6, p.size(), p.toString());
        assertTrue(p.contains("DB_PASSWORD is not set"));
        assertTrue(p.stream().noneMatch(x -> x.contains(TokenEncryptionService.DEV_DEFAULT_KEY)));
        Map<String, String> noKey = safe();
        noKey.remove("TOKEN_ENCRYPTION_KEY");
        assertTrue(ProductionSafetyCheck.problems(noKey::get, true).get(0).startsWith("TOKEN_ENCRYPTION_KEY is not set"));
    }

    @Test
    @DisplayName("a metrics scrape token is optional, but a short one is refused")
    void metricsScrapeToken() {
        Map<String, String> m = safe();
        m.put("app.security.metrics-scrape-token", "");
        assertEquals(List.of(), ProductionSafetyCheck.problems(m::get, true));
        m.put("app.security.metrics-scrape-token", "short-token");
        assertEquals(List.of("METRICS_SCRAPE_TOKEN is shorter than 24 characters"), ProductionSafetyCheck.problems(m::get, true));
        m.put("app.security.metrics-scrape-token", "0123456789abcdef0123456789abcdef");
        assertEquals(List.of(), ProductionSafetyCheck.problems(m::get, true));
    }
}
