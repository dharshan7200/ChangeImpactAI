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

    @Getter
    @Setter
    public static class Security {
        private JWT jwt = new JWT();
        private BCrypt bcrypt = new BCrypt();
        private List<String> allowedOrigins = List.of("http://localhost:5173");
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
            private int authRequestsPerMinute = 10;
            private int apiRequestsPerMinute = 100;
        }
    }

    @Getter
    @Setter
    public static class GitHub {
        private String appId;
        private String privateKeyPath;
        private String webhookSecret;
        private String apiBaseUrl = "https://api.github.com";
        private int cloneTimeoutSeconds = 300;
        private int maxRepoSizeMb = 500;
        private OAuth oauth = new OAuth();

        @Getter
        @Setter
        public static class OAuth {
            private String clientId;
            private String clientSecret;
        }
    }

    @Getter
    @Setter
    public static class Ingestion {
        private String workspaceBasePath;
        private int maxConcurrentJobs = 3;
        private int jobTimeoutMinutes = 30;
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
}

