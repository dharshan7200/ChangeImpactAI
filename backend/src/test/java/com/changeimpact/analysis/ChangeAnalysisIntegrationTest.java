package com.changeimpact.analysis;

import com.changeimpact.analysis.dto.CreateAnalysisRequest;
import com.changeimpact.analysis.entity.ChangeAnalysis;
import com.changeimpact.analysis.entity.ChangedSymbol;
import com.changeimpact.analysis.entity.ImpactFinding;
import com.changeimpact.analysis.repository.ChangeAnalysisRepository;
import com.changeimpact.analysis.repository.ChangedSymbolRepository;
import com.changeimpact.analysis.repository.ImpactFindingRepository;
import com.changeimpact.analysis.service.ChangeAnalysisService;
import com.changeimpact.auth.dto.RegisterRequest;
import com.changeimpact.auth.service.AuthService;
import com.changeimpact.reporting.service.ImpactReportService;
import com.changeimpact.repository.dto.ConnectRepositoryRequest;
import com.changeimpact.repository.repository.RepositoryRepository;
import com.changeimpact.repository.service.RepositoryService;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 acceptance test on real infrastructure (PostgreSQL + pgvector and RabbitMQ via Testcontainers):
 * given two commit IDs of a real Git repository, the platform identifies the code changes, traverses the
 * dependency graph, scores risk and produces a traceable report — through the asynchronous job path.
 *
 * <p>The Git repository is built from the {@code shop} (v1) and {@code shop-v2} test fixtures.
 * Skipped automatically when Docker is not available.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Integration: change impact analysis (PostgreSQL + RabbitMQ + JGit)")
class ChangeAnalysisIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine").asCompatibleSubstituteFor("rabbitmq"));

    @TempDir
    static Path workspaces;

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
        registry.add("app.ingestion.workspace-base-path", () -> workspaces.resolve("ws").toString());
        registry.add("app.ingestion.allow-local-repositories", () -> "true"); // the test repository is a local file:// origin
    }

    @Autowired AuthService authService;
    @Autowired RepositoryService repositoryService;
    @Autowired com.changeimpact.repository.repository.UserRepository userRepository;
    @Autowired RepositoryRepository repositoryRepository;
    @Autowired ChangeAnalysisService changeAnalysisService;
    @Autowired ChangeAnalysisRepository analysisRepository;
    @Autowired ChangedSymbolRepository changedSymbolRepository;
    @Autowired ImpactFindingRepository impactFindingRepository;
    @Autowired ImpactReportService reportService;

    @Test
    @DisplayName("two commits → semantic changes, impact paths, risk and report (async via RabbitMQ)")
    void analysesTwoCommitsEndToEnd() throws Exception {
        // ---- a real Git repository with two commits: shop v1 → shop v2
        Path origin = workspaces.resolve("origin");
        RevCommit v1;
        RevCommit v2;
        try (Git git = Git.init().setDirectory(origin.toFile()).setInitialBranch("main").call()) {
            FileUtils.copyDirectory(fixture("shop").toFile(), origin.toFile());
            v1 = commit(git, "shop v1");
            FileUtils.deleteDirectory(origin.resolve("src").toFile());
            FileUtils.copyDirectory(fixture("shop-v2").toFile(), origin.toFile());
            v2 = commit(git, "Add coupon code to orders");
        }

        // ---- user + repository (cloned from the local origin)
        var auth = authService.register(new RegisterRequest("it@example.com", "ituser", "Passw0rd!123", "IT User"));
        UUID userId = auth.user().id();
        // registered directly: "connect" verifies that the GitHub repository exists, this one is a local origin
        var repo = repositoryRepository.save(com.changeimpact.repository.entity.Repository.builder()
                .owner(userRepository.findById(userId).orElseThrow()).fullName("local/shop").name("shop")
                .cloneUrl(origin.toUri().toString()).htmlUrl("https://example.invalid/local/shop").defaultBranch("main").build());

        // ---- request the analysis; the RabbitMQ worker executes it asynchronously
        ChangeAnalysis created = changeAnalysisService.create(userId,
                new CreateAnalysisRequest(repo.getId(), v1.name(), v2.name(), 3, List.of(), false));
        ChangeAnalysis done = awaitTerminal(created.getId(), Duration.ofSeconds(120));
        assertEquals(ChangeAnalysis.Status.COMPLETED, done.getStatus(), "error: " + done.getErrorMessage());
        assertEquals(v1.name(), done.getBaseCommit());
        assertEquals(v2.name(), done.getHeadCommit());
        assertNotNull(done.getRiskLevel());

        Map<String, ChangedSymbol> changed = changedSymbolRepository
                .findByAnalysisIdOrderByRiskScoreDescQualifiedNameAsc(done.getId()).stream()
                .collect(Collectors.toMap(c -> c.getQualifiedName().replace("com.shop.", ""), c -> c));

        // semantic classification
        ChangedSymbol signature = changed.get("service.OrderService#placeOrder(String,int,String)");
        assertNotNull(signature, changed.keySet().toString());
        assertTrue(List.of(signature.getChangeKinds()).contains("PARAMETERS_CHANGED"));
        assertTrue(signature.isBreaking());
        assertTrue(List.of(changed.get("repo.OrderRepository#findAll()").getChangeKinds()).contains("REMOVED"));
        assertTrue(List.of(changed.get("payment.stripe.StripeGateway").getChangeKinds()).contains("MOVED"));
        assertFalse(changed.containsKey("domain.Order#markPaid()"), "comment/formatting-only change must not be reported");

        // impact propagation with verified paths
        List<ImpactFinding> sigImpacts = impactFindingRepository.findByAnalysisId(done.getId()).stream()
                .filter(f -> f.getChangedSymbolId().equals(signature.getId())).toList();
        ImpactFinding controller = sigImpacts.stream()
                .filter(f -> f.getAffectedQualifiedName().equals("com.shop.web.OrderController#create(String,int)"))
                .findFirst().orElseThrow(() -> new AssertionError("controller not impacted: " + sigImpacts));
        assertEquals("DIRECT", controller.getImpactType());
        assertEquals("VERIFIED_REFERENCE", controller.getEvidence());
        assertTrue(controller.isAlsoChanged(), "the caller was updated in the same change");
        assertEquals(2, controller.getDependencyPath().size());

        // removed method: dependents come from the BASE commit's graph
        ChangedSymbol removed = changed.get("repo.OrderRepository#findAll()");
        assertTrue(impactFindingRepository.findByAnalysisId(done.getId()).stream()
                .anyMatch(f -> f.getChangedSymbolId().equals(removed.getId())
                        && f.getAffectedQualifiedName().equals("com.shop.service.OrderService#listOrders()")
                        && f.getGraphCommit().equals(v1.name())));

        // traceable report
        Map<String, Object> report = reportService.buildReport(done);
        assertNotNull(report.get("verificationSteps"));
        String markdown = reportService.toMarkdown(report);
        assertTrue(markdown.contains("placeOrder"));
        assertTrue(markdown.contains("not a statistically calibrated probability"));
    }

    private ChangeAnalysis awaitTerminal(UUID id, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ChangeAnalysis a = analysisRepository.findById(id).orElseThrow();
            if (a.getStatus() == ChangeAnalysis.Status.COMPLETED || a.getStatus() == ChangeAnalysis.Status.FAILED) {
                return a;
            }
            Thread.sleep(500);
        }
        fail("analysis did not finish within " + timeout);
        return null;
    }

    private static RevCommit commit(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        git.add().addFilepattern(".").setUpdate(true).call();
        return git.commit().setMessage(message).setSign(false)
                .setAuthor("it", "it@example.com").setCommitter("it", "it@example.com").call();
    }

    private static Path fixture(String name) throws Exception {
        Path p = Paths.get(Objects.requireNonNull(ChangeAnalysisIntegrationTest.class.getResource("/sample-projects/" + name)).toURI());
        assertTrue(Files.isDirectory(p));
        return p;
    }
}
