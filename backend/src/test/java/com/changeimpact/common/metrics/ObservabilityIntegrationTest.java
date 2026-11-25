package com.changeimpact.common.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The Prometheus endpoint is readable with the scrape token or as an administrator only, and exposes the platform's
 * own metrics next to the JVM, HTTP, database pool and RabbitMQ metrics.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Integration: metrics endpoint access and content")
class ObservabilityIntegrationTest {

    static final String SCRAPE_TOKEN = "test-scrape-token-0123456789abcdef";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine").asCompatibleSubstituteFor("rabbitmq"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("app.security.admin-emails", () -> "metrics-admin@example.com");
        registry.add("app.security.metrics-scrape-token", () -> SCRAPE_TOKEN);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired WebApplicationContext context;
    @Autowired QueueMetrics queueMetrics;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String register(String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + name + "@example.com\",\"username\":\"" + name.replace("-", "") + "\","
                        + "\"password\":\"Passw0rd!123\",\"fullName\":\"" + name + "\"}")).andReturn();
        return JSON.readTree(r.getResponse().getContentAsString()).path("data").path("accessToken").asString();
    }

    private MvcResult scrape(String authorization) throws Exception {
        var request = get("/actuator/prometheus");
        if (authorization != null) request.header("Authorization", authorization);
        return mvc.perform(request).andReturn();
    }

    @Test
    @DisplayName("scrape token or admin only; every other actuator endpoint except health/info is admin-only")
    void access() throws Exception {
        String user = register("metrics-user-" + UUID.randomUUID().toString().substring(0, 8));
        String admin = register("metrics-admin");
        assertFalse(user.isBlank(), "user token");
        assertEquals(200, mvc.perform(get("/api/v1/repositories").header("Authorization", "Bearer " + user)).andReturn().getResponse().getStatus());

        assertEquals(401, scrape(null).getResponse().getStatus());
        assertEquals(401, scrape("Bearer wrong-token-0123456789abcdef-xyz").getResponse().getStatus());
        assertEquals(403, scrape("Bearer " + user).getResponse().getStatus());
        assertEquals(200, scrape("Bearer " + admin).getResponse().getStatus());
        assertEquals(200, scrape("Bearer " + SCRAPE_TOKEN).getResponse().getStatus());

        assertEquals(403, mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + user)).andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + admin)).andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("platform metrics, queue gauges and the standard JVM/HTTP/pool/RabbitMQ metrics are exposed")
    void content() throws Exception {
        PlatformMetrics.job("indexing", "completed");
        PlatformMetrics.stageTimings("indexing", Map.of("PARSING", 1200L, "TOTAL", 1500L));
        PlatformMetrics.webhook("pull_request", "ACCEPTED");
        PlatformMetrics.webhook("something-unknown", "IGNORED");
        queueMetrics.refresh();

        String body = scrape("Bearer " + SCRAPE_TOKEN).getResponse().getContentAsString();
        for (String expected : new String[]{
                "changeimpact_jobs_total{application=\"changeimpact-ai\",outcome=\"completed\",type=\"indexing\"}",
                "changeimpact_pipeline_stage_seconds_count{application=\"changeimpact-ai\",pipeline=\"indexing\",stage=\"PARSING\"}",
                "changeimpact_pipeline_stage_seconds_bucket{",
                "changeimpact_webhooks_total{application=\"changeimpact-ai\",event=\"pull_request\",status=\"ACCEPTED\"}",
                "changeimpact_webhooks_total{application=\"changeimpact-ai\",event=\"other\",status=\"IGNORED\"}",
                "changeimpact_queue_messages{application=\"changeimpact-ai\",queue=\"changeimpact.analysis.job\"} 0",
                "changeimpact_queue_consumers{application=\"changeimpact-ai\",queue=\"changeimpact.analysis.job\"}",
                "changeimpact_jobs_active{application=\"changeimpact-ai\",status=\"RUNNING\",type=\"CHANGE_ANALYSIS\"} 0",
                "jvm_memory_used_bytes", "hikaricp_connections_active", "rabbitmq_"}) { // HTTP metrics: not with MockMvc
            assertTrue(body.contains(expected), "missing in scrape: " + expected);
        }
        assertFalse(body.contains("something-unknown"), "webhook event tags are bounded");
    }
}
