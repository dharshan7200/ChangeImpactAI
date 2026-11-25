package com.changeimpact.common.config;

import com.changeimpact.github.service.TokenEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/**
 * Runs when the environment is prepared, before any bean or connection exists. With the {@code prod} profile it
 * refuses to start when a required secret is unset or still a published development default; in other profiles it
 * only warns. Only the setting names are reported, never their values.
 */
public class ProductionSafetyCheck implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyCheck.class);
    static final String UNSET = "\u0000unset:";

    static final Set<String> KNOWN_JWT_DEFAULTS = Set.of(
            "changeimpact-jwt-secret-key-must-be-at-least-32-characters-long-for-hs256",
            "dev-only-secret-key-not-for-production-must-be-32-chars");

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment env = event.getEnvironment();
        boolean prod = Arrays.asList(env.getActiveProfiles()).contains("prod");
        List<String> problems = problems(key -> resolve(env, key), prod);
        if (problems.isEmpty()) return;
        if (prod) {
            throw new IllegalStateException("Unsafe production configuration:\n - " + String.join("\n - ", problems));
        }
        log.warn("Development defaults in use (not allowed with APP_PROFILE=prod): {}", String.join("; ", problems));
    }

    /** Property value, or {@code UNSET + variable} when a placeholder without default cannot be resolved. */
    private static String resolve(ConfigurableEnvironment env, String key) {
        try {
            return env.getProperty(key);
        } catch (IllegalArgumentException e) { // "Could not resolve placeholder 'X' in value ..."
            String m = String.valueOf(e.getMessage());
            int a = m.indexOf('\''), b = a < 0 ? -1 : m.indexOf('\'', a + 1);
            return UNSET + (b > a ? m.substring(a + 1, b) : key);
        }
    }

    static List<String> problems(Function<String, String> prop, boolean prod) {
        List<String> p = new ArrayList<>();
        String jwt = prop.apply("app.security.jwt.secret");
        if (unset(jwt)) p.add(name(jwt) + " is not set");
        else if (jwt == null || KNOWN_JWT_DEFAULTS.contains(jwt)) p.add("JWT_SECRET is a published development default");
        else if (jwt.getBytes(StandardCharsets.UTF_8).length < 32) p.add("JWT_SECRET is shorter than 32 bytes");

        String key = prop.apply("TOKEN_ENCRYPTION_KEY");
        if (key == null || TokenEncryptionService.DEV_DEFAULT_KEY.equals(key)) {
            p.add("TOKEN_ENCRYPTION_KEY is " + (key == null ? "not set" : "the published development key")
                    + " (generate one with: openssl rand -base64 32)");
        }
        String db = prop.apply("spring.datasource.password");
        if (unset(db)) p.add(name(db) + " is not set");
        else if ("changeimpact".equals(db)) p.add("DB_PASSWORD is the development default");

        String ru = prop.apply("spring.rabbitmq.username"), rp = prop.apply("spring.rabbitmq.password");
        if (unset(ru)) p.add(name(ru) + " is not set");
        if (unset(rp)) p.add(name(rp) + " is not set");
        if ("guest".equals(ru) && "guest".equals(rp)) p.add("RabbitMQ uses guest/guest");

        String bcrypt = prop.apply("app.security.bcrypt.strength");
        try {
            if (bcrypt != null && !unset(bcrypt) && Integer.parseInt(bcrypt.trim()) < 10) p.add("BCRYPT_STRENGTH below 10");
        } catch (NumberFormatException ignored) {
            p.add("BCRYPT_STRENGTH is not a number");
        }
        String scrape = prop.apply("app.security.metrics-scrape-token");
        if (notBlank(scrape) && !unset(scrape) && scrape.length() < 24) p.add("METRICS_SCRAPE_TOKEN is shorter than 24 characters");

        String origin = prop.apply("app.security.allowed-origins[0]");
        if (unset(origin)) p.add(name(origin) + " is not set (public URL of the frontend, used for CORS)");

        boolean appConfigured = notBlank(prop.apply("app.github.app-id")) || notBlank(prop.apply("app.github.client-id"));
        if (appConfigured && !notBlank(prop.apply("app.github.webhook-secret"))) {
            p.add("GitHub App configured without GITHUB_WEBHOOK_SECRET");
        }
        if ("true".equalsIgnoreCase(prop.apply("app.ingestion.allow-local-repositories"))) {
            p.add("local (file://) repositories are allowed (ALLOW_LOCAL_REPOSITORIES)");
        }
        return p;
    }

    private static boolean unset(String v) {
        return v != null && v.startsWith(UNSET);
    }

    private static String name(String v) {
        return v.substring(UNSET.length());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank() && !unset(s);
    }
}
