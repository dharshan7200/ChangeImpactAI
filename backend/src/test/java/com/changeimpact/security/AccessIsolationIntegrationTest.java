package com.changeimpact.security;

import com.changeimpact.analysis.entity.ChangeAnalysis;
import com.changeimpact.analysis.repository.ChangeAnalysisRepository;
import com.changeimpact.github.app.PullRequest;
import com.changeimpact.github.app.PullRequestRepository;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.entity.User;
import com.changeimpact.repository.repository.RepositoryRepository;
import com.changeimpact.repository.repository.UserRepository;
import com.changeimpact.testing.reports.entity.QualityReport;
import com.changeimpact.testing.reports.repository.QualityReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant isolation through the full HTTP + Spring Security stack against a real PostgreSQL: user B must not see or
 * modify anything that belongs to user A (repositories, analyses and their sub-resources, reports, pull requests,
 * dashboard aggregates). Also checks that unauthenticated calls are rejected and that login is rate limited.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Integration: tenant isolation, authentication and rate limiting")
class AccessIsolationIntegrationTest {

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
        registry.add("app.security.rate-limit.auth-requests-per-minute", () -> "6");
        registry.add("app.security.admin-emails", () -> "platform-admin@example.com");
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired RepositoryRepository repositories;
    @Autowired ChangeAnalysisRepository analyses;
    @Autowired QualityReportRepository reports;
    @Autowired PullRequestRepository pullRequests;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private MockMvc mvc;
    private static String tokenA, tokenB, tokenAdmin;
    private static UUID repoA, analysisA;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        if (tokenA != null) return;
        tokenA = register("owner-a");
        tokenB = register("intruder-b");
        tokenAdmin = register("platform-admin");
        User a = users.findByEmail("owner-a@example.com").orElseThrow();
        Repository repo = repositories.save(Repository.builder().owner(a).fullName("owner-a/private-service").name("private-service")
                .cloneUrl("https://github.com/owner-a/private-service.git").htmlUrl("https://github.com/owner-a/private-service").build());
        repoA = repo.getId();
        ChangeAnalysis analysis = ChangeAnalysis.builder().repositoryId(repoA).ownerId(a.getId()).baseRef("main~1").headRef("main")
                .riskLevel("HIGH").riskScore(BigDecimal.valueOf(61.5)).summary(Map.of("filesChanged", 3)).build();
        analysis.setStatus(ChangeAnalysis.Status.COMPLETED);
        analysisA = analyses.save(analysis).getId();
        reports.save(QualityReport.builder().repositoryId(repoA).commitSha("a".repeat(40)).reportType(QualityReport.Type.JACOCO)
                .uploadedBy(a.getId()).summary(Map.of("lineCoverage", 81.0)).build());
        pullRequests.save(PullRequest.builder().repositoryId(repoA).number(4).state("open").title("secret work").build());
    }

    private String register(String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + name + "@example.com\",\"username\":\"" + name.replace("-", "") + "\","
                                + "\"password\":\"Passw0rd!123\",\"fullName\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JSON.readTree(r.getResponse().getContentAsString()).path("data").path("accessToken").asString();
    }

    private int statusOf(String token, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("the owner can read every resource; another user gets 404 for all of them (no existence leak)")
    void isolation() throws Exception {
        List<String> urls = List.of(
                "/api/v1/repositories/" + repoA,
                "/api/v1/repositories/" + repoA + "/reports",
                "/api/v1/repositories/" + repoA + "/pull-requests",
                "/api/v1/analyses/" + analysisA,
                "/api/v1/analyses/" + analysisA + "/changes",
                "/api/v1/analyses/" + analysisA + "/impacts",
                "/api/v1/analyses/" + analysisA + "/risk",
                "/api/v1/analyses/" + analysisA + "/tests",
                "/api/v1/analyses/" + analysisA + "/security",
                "/api/v1/analyses/" + analysisA + "/report",
                "/api/v1/analyses/" + analysisA + "/report.md");
        for (String url : urls) {
            assertEquals(200, statusOf(tokenA, url), "owner: " + url);
            assertEquals(404, statusOf(tokenB, url), "other user: " + url);
        }
    }

    @Test
    @DisplayName("another user cannot upload reports to, analyse or index a foreign repository")
    void noWrites() throws Exception {
        int reportsBefore = reports.findByRepositoryIdOrderByCreatedAtDesc(repoA).size();
        MockMultipartFile file = new MockMultipartFile("file", "jacoco.xml", "application/xml", "<report name=\"x\"/>".getBytes());
        assertEquals(404, mvc.perform(multipart("/api/v1/repositories/" + repoA + "/reports").file(file)
                        .param("type", "JACOCO").param("commitSha", "a".repeat(40)).header("Authorization", "Bearer " + tokenB))
                .andReturn().getResponse().getStatus());
        assertEquals(404, mvc.perform(post("/api/v1/analyses").header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryId\":\"" + repoA + "\",\"baseRef\":\"main~1\",\"headRef\":\"main\"}"))
                .andReturn().getResponse().getStatus());
        assertEquals(404, mvc.perform(post("/api/v1/repositories/" + repoA + "/index").header("Authorization", "Bearer " + tokenB))
                .andReturn().getResponse().getStatus());
        assertEquals(reportsBefore, reports.findByRepositoryIdOrderByCreatedAtDesc(repoA).size(), "nothing was added");
    }

    @Test
    @DisplayName("lists and aggregates only contain the caller's own data")
    void aggregates() throws Exception {
        String dashB = mvc.perform(get("/api/v1/dashboard").header("Authorization", "Bearer " + tokenB)).andReturn().getResponse().getContentAsString();
        assertFalse(dashB.contains("private-service") || dashB.contains("secret work") || dashB.contains(analysisA.toString()), dashB);
        String reposB = mvc.perform(get("/api/v1/repositories").header("Authorization", "Bearer " + tokenB)).andReturn().getResponse().getContentAsString();
        assertFalse(reposB.contains("private-service"));
        String appB = mvc.perform(get("/api/v1/github/app").header("Authorization", "Bearer " + tokenB)).andReturn().getResponse().getContentAsString();
        assertFalse(appB.contains("private-service"));
        String dashA = mvc.perform(get("/api/v1/dashboard").header("Authorization", "Bearer " + tokenA)).andReturn().getResponse().getContentAsString();
        assertTrue(dashA.contains("private-service"));
    }

    @Test
    @DisplayName("unauthenticated and forged tokens are rejected")
    void authentication() throws Exception {
        assertEquals(401, mvc.perform(get("/api/v1/analyses/" + analysisA)).andReturn().getResponse().getStatus());
        assertEquals(401, statusOf("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.invalid-signature", "/api/v1/analyses/" + analysisA));
    }

    @Test
    @DisplayName("login is rate limited per client IP (429 with Retry-After)")
    void loginRateLimit() throws Exception {
        int limited = 0;
        String retryAfter = null;
        for (int i = 0; i < 10; i++) {
            var res = mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("203.0.113.7"); return r; })
                    .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"owner-a@example.com\",\"password\":\"wrong\"}"))
                    .andReturn().getResponse();
            if (res.getStatus() == 429) {
                limited++;
                retryAfter = res.getHeader("Retry-After");
            }
        }
        assertEquals(4, limited, "6 attempts per minute allowed, the remaining 4 refused");
        assertNotNull(retryAfter);
        // another client is not affected
        assertNotEquals(429, mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("198.51.100.9"); return r; })
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"owner-a@example.com\",\"password\":\"wrong\"}"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("admin endpoints require ROLE_ADMIN (granted via ADMIN_EMAILS); retention removes old data and keeps the latest report")
    void adminAndRetention() throws Exception {
        assertEquals(403, mvc.perform(post("/api/v1/admin/retention/run").header("Authorization", "Bearer " + tokenA))
                .andReturn().getResponse().getStatus());
        User a = users.findByEmail("owner-a@example.com").orElseThrow();
        ChangeAnalysis old = ChangeAnalysis.builder().repositoryId(repoA).ownerId(a.getId()).baseRef("x").headRef("y").build();
        old.setStatus(ChangeAnalysis.Status.COMPLETED);
        UUID oldId = analyses.save(old).getId();
        jdbc.update("UPDATE change_analyses SET created_at = now() - interval '400 days' WHERE id = ?", oldId);
        UUID oldReport = reports.save(QualityReport.builder().repositoryId(repoA).commitSha("b".repeat(40)).reportType(QualityReport.Type.PIT)
                .uploadedBy(a.getId()).summary(Map.of()).build()).getId();
        jdbc.update("UPDATE quality_reports SET created_at = now() - interval '400 days' WHERE id = ?", oldReport);

        var res = mvc.perform(post("/api/v1/admin/retention/run").header("Authorization", "Bearer " + tokenAdmin)).andReturn().getResponse();
        assertEquals(200, res.getStatus(), res.getContentAsString());
        assertTrue(analyses.findById(oldId).isEmpty(), "400-day-old analysis removed");
        assertTrue(analyses.findById(analysisA).isPresent(), "recent analysis kept");
        assertTrue(reports.findById(oldReport).isPresent(), "latest PIT report of the repository is kept even when old");
    }
}
