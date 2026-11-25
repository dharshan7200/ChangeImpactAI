package com.changeimpact.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Strongly-typed configuration properties for ChangeImpact AI.
 *
 * <p>All externally configurable values are bound from application.yml under
 * the {@code app} prefix. This eliminates magic strings and provides type safety,
 * documentation and IDE completion for configuration.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Security security = new Security();
    private GitHub github = new GitHub();
    private Ingestion ingestion = new Ingestion();
    private RabbitMQ rabbitmq = new RabbitMQ();
    private History history = new History();
    private Impact impact = new Impact();
    private Risk risk = new Risk();
    private Retention retention = new Retention();

    /** Data retention; a value of 0 keeps data forever. */
    @Getter
    @Setter
    public static class Retention {
        private boolean enabled = true;
        private String cron = "0 30 3 * * *";
        /** Completed/failed change analyses (with their findings, tests and security results). */
        private int analysesDays = 180;
        private int webhookDeliveriesDays = 30;
        /** Quality reports; the latest report of each type per repository is always kept. */
        private int qualityReportsDays = 90;
        /** Leftover commit snapshot directories (normally deleted right after use). */
        private int staleSnapshotHours = 6;
    }

    @Getter
    @Setter
    public static class Security {
        private JWT jwt = new JWT();
        private BCrypt bcrypt = new BCrypt();
        private List<String> allowedOrigins = List.of("http://localhost:5173");
        /** Users with these e-mail addresses are granted ROLE_ADMIN (at startup and on registration). */
        private List<String> adminEmails = List.of();
        /** Bearer token Prometheus presents to scrape /actuator/prometheus; empty = only admins may read metrics. */
        private String metricsScrapeToken = "";
        private RateLimit rateLimit = new RateLimit();

        @Getter
        @Setter
        public static class JWT {
            private String secret;
            private long accessTokenExpiryMs = 900_000L;      // 15 min
            private long refreshTokenExpiryMs = 604_800_000L; // 7 days
        }

        @Getter
        @Setter
        public static class BCrypt {
            private int strength = 12;
        }

        @Getter
        @Setter
        public static class RateLimit {
            private boolean enabled = true;
            /** Per client IP: login, registration and token refresh (brute-force protection). */
            private int authRequestsPerMinute = 10;
            /** Per authenticated user (per IP for anonymous calls). The UI polls running jobs, so keep headroom. */
            private int apiRequestsPerMinute = 300;
            /** Per client IP on the webhook endpoint (signatures are verified, but bodies cost bandwidth). */
            private int webhookRequestsPerMinute = 600;
        }
    }

    @Getter
    @Setter
    public static class GitHub {
        /** GitHub App ID (numeric); used as JWT issuer when no client ID is configured. */
        private String appId;
        /** GitHub App client ID (Iv1.../Iv23...); GitHub's recommended JWT issuer. */
        private String clientId;
        /** App slug from the App's URL, used to show the installation link. */
        private String appSlug;
        /** Path of the App's private key (PEM, PKCS#1 or PKCS#8). */
        private String privateKeyPath;
        /** Alternative to privateKeyPath: the PEM content itself (e.g. from a secret store). */
        private String privateKey;
        private String webhookSecret;
        private String apiBaseUrl = "https://api.github.com";
        /** Public base URL of the ChangeImpact AI frontend, for Check Run "details" links (optional). */
        private String publicUrl;
        private boolean analyzeDraftPullRequests = false;
        private int webhookMaxBodyBytes = 10 * 1024 * 1024;
        private int cloneTimeoutSeconds = 300;
        private int maxRepoSizeMb = 500;
        private OAuth oauth = new OAuth();
        private Checks checks = new Checks();

        @Getter
        @Setter
        public static class Checks {
            private boolean enabled = true;
            private String name = "ChangeImpact AI";
            /** Lowest overall risk level that makes the Check Run fail: LOW, MEDIUM, HIGH, CRITICAL or NEVER. */
            private String failOn = "CRITICAL";
            /**
             * Lowest severity of a vulnerability introduced by the change (Semgrep match in changed lines, known CVE in an
             * added/changed dependency) that makes the Check Run fail: LOW, MEDIUM, HIGH, CRITICAL or NEVER.
             * Security-sensitive-area hints (STATIC_RULES) never fail a check.
             */
            private String failOnSecurity = "HIGH";
            /** Upper bound on annotations per Check Run (sent in batches of 50, GitHub's per-request limit). */
            private int maxAnnotations = 150;
        }

        @Getter
        @Setter
        public static class OAuth {
            private String clientId;
            private String clientSecret;
            /** GitHub web origin for /login/oauth/* (GitHub Enterprise: https://<host>). */
            private String webBaseUrl = "https://github.com";
            /** Frontend page GitHub redirects back to; must equal the OAuth App's callback URL. */
            private String redirectUri;
            /** Space-separated scopes: {@code repo} grants access to private repositories. */
            private String scopes = "repo read:user user:email";

            public boolean isConfigured() {
                return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
            }
        }
    }

    @Getter
    @Setter
    public static class Ingestion {
        private String workspaceBasePath;
        /** Hosts Git may clone/fetch from (plus the host of app.github.api-base-url for GitHub Enterprise). */
        private List<String> allowedGitHosts = List.of("github.com");
        /** Allow hosts resolving to loopback/private/link-local addresses (self-hosted Git on a private network). */
        private boolean allowPrivateGitAddresses = false;
        /** Allow file:// repositories (tests and local demos only; refused by the prod safety check). */
        private boolean allowLocalRepositories = false;
        /** Upper bound of parallel consumers per job queue (indexing, change analysis). Work on one repository is serialised. */
        private int maxConcurrentJobs = 3;
        /** A RUNNING job older than this is marked FAILED by the watchdog. */
        private int jobTimeoutMinutes = 30;
        /** A job still QUEUED after this long (lost message, broker outage) is marked FAILED. */
        private int queuedTimeoutMinutes = 720;
        private int maxFilesPerRepository = 10_000;
        private List<String> excludedPaths = List.of(
                "target/", "build/", ".gradle/", "node_modules/",
                ".git/", "out/", "bin/", "generated/", "generated-sources/"
        );
    }

    @Getter
    @Setter
    public static class RabbitMQ {
        private Queues queues = new Queues();
        private Exchanges exchanges = new Exchanges();

        @Getter
        @Setter
        public static class Queues {
            private String repositoryIndex = "changeimpact.repository.index";
            private String repositoryIndexDlq = "changeimpact.repository.index.dlq";
            private String analysisJob = "changeimpact.analysis.job";
            private String analysisJobDlq = "changeimpact.analysis.job.dlq";
        }

        @Getter
        @Setter
        public static class Exchanges {
            private String main = "changeimpact.main";
            private String deadLetter = "changeimpact.dead-letter";
        }
    }

    /** Commit history intelligence (Phase 3). */
    @Getter
    @Setter
    public static class History {
        /** Commits reachable from HEAD that are scanned for file churn and revert/bug-fix flags. */
        private int maxCommits = 300;
    }

    /** Impact propagation (Phase 3). */
    @Getter
    @Setter
    public static class Impact {
        private int defaultMaxDepth = 3;
        private int maxDepthLimit = 6;
        /** Strength multiplier applied per hop after the first (behavioural impact fades with distance). */
        private double depthDecay = 0.85;
        private int maxFindingsPerChange = 500;
        /**
         * How strongly a change propagates across each relationship kind (0..1). Inheritance and calls carry
         * contracts directly; type mentions and imports are weaker evidence of behavioural impact.
         */
        private java.util.Map<String, Double> relationshipWeights = new java.util.LinkedHashMap<>(java.util.Map.of(
                "EXTENDS", 1.0, "IMPLEMENTS", 1.0, "METHOD_CALL", 0.9, "CONSTRUCTOR_CALL", 0.9,
                "FIELD_ACCESS", 0.8, "RETURN_TYPE", 0.6, "PARAMETER_TYPE", 0.6, "FIELD_TYPE", 0.6,
                "ANNOTATION", 0.4, "IMPORT", 0.3));
    }

    /**
     * Transparent weighted risk heuristic (Phase 3). Scores are 0..100 points, NOT probabilities.
     * Every weight is configurable; factor definitions are documented in RiskScoringService.
     */
    @Getter
    @Setter
    public static class Risk {
        private java.util.Map<String, Double> weights = new java.util.LinkedHashMap<>(java.util.Map.ofEntries(
                java.util.Map.entry("changeSeverity", 0.25),
                java.util.Map.entry("directDependents", 0.15),
                java.util.Map.entry("transitiveDependents", 0.10),
                java.util.Map.entry("propagationDepth", 0.05),
                java.util.Map.entry("publicApiExposure", 0.10),
                java.util.Map.entry("complexity", 0.05),
                java.util.Map.entry("moduleCriticality", 0.05),
                java.util.Map.entry("historicalChurn", 0.05),
                java.util.Map.entry("testReachability", 0.10),
                java.util.Map.entry("architectureViolations", 0.05),
                // only applies when a JaCoCo report of the head commit was uploaded
                java.util.Map.entry("changedLineCoverage", 0.10)));
        private double mediumThreshold = 25;
        private double highThreshold = 50;
        private double criticalThreshold = 75;
        /** Package/path regexes (case-insensitive) and their criticality 0..1; first match wins. */
        private List<CriticalModule> criticalModules = List.of(
                new CriticalModule("(^|[./])(security|auth|authentication|authorization|crypto|payment|billing)([./]|$)", 1.0),
                new CriticalModule("(^|[./])(config|configuration|persistence|migration)([./]|$)", 0.6));

        @Getter
        @Setter
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class CriticalModule {
            private String pattern;
            private double criticality;
        }
    }
}
