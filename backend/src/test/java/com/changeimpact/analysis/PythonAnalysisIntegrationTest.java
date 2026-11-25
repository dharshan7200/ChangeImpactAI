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
import com.changeimpact.repository.repository.RepositoryRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The full change-analysis pipeline on a Python repository (experimental language, P6-7). */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Integration: change impact analysis of a Python repository (tree-sitter)")
class PythonAnalysisIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine").asCompatibleSubstituteFor("rabbitmq"));

    @TempDir static Path workspaces;

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
        registry.add("app.ingestion.allow-local-repositories", () -> "true");
        registry.add("app.analysis.languages", () -> "java,python");
    }

    @Autowired AuthService authService;
    @Autowired com.changeimpact.repository.repository.UserRepository userRepository;
    @Autowired RepositoryRepository repositoryRepository;
    @Autowired ChangeAnalysisService changeAnalysisService;
    @Autowired ChangeAnalysisRepository analysisRepository;
    @Autowired ChangedSymbolRepository changedSymbolRepository;
    @Autowired ImpactFindingRepository impactFindingRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("signature change of a function → verified impact on its caller; comment-only edit ignored")
    void pythonChange() throws Exception {
        Path origin = workspaces.resolve("origin");
        RevCommit v1, v2;
        try (Git git = Git.init().setDirectory(origin.toFile()).setInitialBranch("main").call()) {
            write(origin, "shop/__init__.py", "");
            write(origin, "shop/models.py", """
                    class Order:
                        def __init__(self, items):
                            self.items = items

                        def key(self):
                            return id(self)

                        def total(self):
                            return sum(self.items)
                    """);
            write(origin, "shop/util.py", "def audit(event):\n    print(event)\n");
            write(origin, "shop/service.py", """
                    from shop.models import Order
                    from shop import util

                    class OrderService:
                        def place(self, items):
                            order = Order(items)
                            util.audit("placed")
                            return order
                    """);
            write(origin, "tests/test_service.py", """
                    from shop.service import OrderService

                    def test_place():
                        assert OrderService().place([1]).items == [1]
                    """);
            v1 = commit(git, "shop v1");
            write(origin, "shop/util.py", "def audit(event, level=\"info\"):\n    print(level, event)\n");
            write(origin, "shop/models.py", """
                    class Order:
                        def __init__(self, items):
                            self.items = items

                        def key(self):
                            # identity is the key
                            return id(self)

                        def total(self):
                            return sum(i for i in self.items if i > 0)
                    """);
            v2 = commit(git, "Audit levels; ignore negative items");
        }

        var auth = authService.register(new com.changeimpact.auth.dto.RegisterRequest("py@example.com", "pyuser", "Passw0rd!123", "Py"));
        UUID userId = auth.user().id();
        var repo = repositoryRepository.save(com.changeimpact.repository.entity.Repository.builder()
                .owner(userRepository.findById(userId).orElseThrow()).fullName("local/pyshop").name("pyshop")
                .cloneUrl(origin.toUri().toString()).htmlUrl("https://example.invalid/local/pyshop").defaultBranch("main").build());

        ChangeAnalysis created = changeAnalysisService.create(userId,
                new CreateAnalysisRequest(repo.getId(), v1.name(), v2.name(), 3, List.of(), false));
        ChangeAnalysis done = awaitTerminal(created.getId(), Duration.ofSeconds(120));
        assertEquals(ChangeAnalysis.Status.COMPLETED, done.getStatus(), "error: " + done.getErrorMessage());

        Map<String, ChangedSymbol> changed = changedSymbolRepository
                .findByAnalysisIdOrderByRiskScoreDescQualifiedNameAsc(done.getId()).stream()
                .collect(Collectors.toMap(ChangedSymbol::getQualifiedName, c -> c, (a, b) -> a));
        ChangedSymbol audit = changed.get("shop.util#audit");
        assertNotNull(audit, changed.keySet().toString());
        // audit(event) → audit(event, level="info"): existing calls still work
        assertTrue(List.of(audit.getChangeKinds()).contains("PARAMETERS_EXTENDED"), List.of(audit.getChangeKinds()).toString());
        assertFalse(audit.isBreaking());
        assertNotNull(changed.get("shop.models.Order#total"), "body change detected: " + changed.keySet());
        assertFalse(changed.containsKey("shop.models.Order#key"), "comment-only change must not be reported");

        List<ImpactFinding> impacts = impactFindingRepository.findByAnalysisId(done.getId()).stream()
                .filter(f -> f.getChangedSymbolId().equals(audit.getId())).toList();
        ImpactFinding caller = impacts.stream()
                .filter(f -> f.getAffectedQualifiedName().equals("shop.service.OrderService#place"))
                .findFirst().orElseThrow(() -> new AssertionError("caller not impacted: " + impacts));
        assertEquals("DIRECT", caller.getImpactType());
        assertEquals("VERIFIED_REFERENCE", caller.getEvidence());
        assertTrue(impacts.stream().anyMatch(f -> f.getAffectedQualifiedName().equals("tests.test_service#test_place") && f.isTest()),
                "the test that calls the caller is reached transitively: " + impacts);

        for (RevCommit c : List.of(v1, v2)) {
            String[] langs = jdbc.queryForObject("SELECT languages FROM analysed_commit_languages WHERE repository_id = ? AND commit_sha = ?",
                    (rs, i) -> (String[]) rs.getArray(1).getArray(), repo.getId(), c.name());
            assertEquals(List.of("java@6", "python@3"), List.of(langs));
            assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM source_files WHERE repository_id = ? AND commit_sha = ? AND language = 'python'",
                    Integer.class, repo.getId(), c.name()));
        }
    }

    private static void write(Path root, String path, String content) throws Exception {
        Path p = root.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private ChangeAnalysis awaitTerminal(UUID id, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ChangeAnalysis a = analysisRepository.findById(id).orElseThrow();
            if (a.getStatus() == ChangeAnalysis.Status.COMPLETED || a.getStatus() == ChangeAnalysis.Status.FAILED) return a;
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
}
