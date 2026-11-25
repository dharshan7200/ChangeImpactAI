package com.changeimpact.analysis;

import com.changeimpact.analysis.service.CommitAnalysisService;
import com.changeimpact.analysis.service.IncrementalAnalysisPlanner;
import com.changeimpact.ingestion.workspace.WorkspaceManager;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.entity.User;
import com.changeimpact.repository.repository.RepositoryRepository;
import com.changeimpact.repository.repository.UserRepository;
import org.apache.commons.io.FileUtils;
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
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Incremental analysis must produce exactly the rows of a full analysis: the same commit is analysed once
 * incrementally (from the previous commit) and once from scratch in a second repository, and every source file,
 * symbol and dependency (incl. its linked target and resolution status) is compared.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Integration: incremental analysis equals a full parse")
class IncrementalAnalysisIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine").asCompatibleSubstituteFor("rabbitmq"));

    @TempDir
    static Path tmp;

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
        registry.add("app.ingestion.workspace-base-path", () -> tmp.resolve("ws").toString());
        registry.add("app.ingestion.allow-local-repositories", () -> "true");
        registry.add("app.ingestion.incremental-max-fraction", () -> "1.0"); // always take the incremental path here
    }

    @Autowired CommitAnalysisService commitAnalysis;
    @Autowired IncrementalAnalysisPlanner planner;
    @Autowired WorkspaceManager workspaceManager;
    @Autowired RepositoryRepository repositories;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("v1→v2→v3→v4 incrementally: every commit's rows equal a full analysis of that commit")
    void incrementalEqualsFull() throws Exception {
        Path origin = tmp.resolve("origin");
        RevCommit v1, v2, v3, v4;
        try (Git git = Git.init().setDirectory(origin.toFile()).setInitialBranch("main").call()) {
            FileUtils.copyDirectory(fixture("shop").toFile(), origin.toFile());
            v1 = commit(git, "shop v1");
            FileUtils.deleteDirectory(origin.resolve("src").toFile());
            FileUtils.copyDirectory(fixture("shop-v2").toFile(), origin.toFile());
            v2 = commit(git, "shop v2");
            // v3: a body change in the web layer and a new, unreferenced utility class
            Path controller = origin.resolve("src/main/java/com/shop/web/OrderController.java");
            Files.writeString(controller, Files.readString(controller).replace("return orderService.summarize(a, b);",
                    "return a == null ? null : orderService.summarize(a, b);"));
            Files.createDirectories(origin.resolve("src/main/java/com/shop/util"));
            Files.writeString(origin.resolve("src/main/java/com/shop/util/Texts.java"),
                    "package com.shop.util;\n\npublic final class Texts {\n    public static String trim(String s) { return s == null ? \"\" : s.trim(); }\n}\n");
            // three declarations named Priority#weight() (abstract + two constant bodies), called from the web layer:
            // in v4 the enum is copied while the caller is re-parsed, and both paths must link the same declaration
            Files.writeString(origin.resolve("src/main/java/com/shop/domain/Priority.java"), """
                    package com.shop.domain;

                    public enum Priority {
                        LOW { @Override public int weight() { return 1; } },
                        HIGH { @Override public int weight() { return 10; } };

                        public abstract int weight();
                    }
                    """);
            Files.writeString(origin.resolve("src/main/java/com/shop/web/PriorityView.java"), """
                    package com.shop.web;

                    import com.shop.domain.Priority;
                    import com.shop.repo.OrderRepository;

                    public class PriorityView {
                        private OrderRepository repository;

                        public int high() { return Priority.HIGH.weight(); }
                    }
                    """);
            v3 = commit(git, "shop v3");
            // v4: the repository returns Order instead of Optional<Order> — callers in unchanged files resolve differently
            Path repo = origin.resolve("src/main/java/com/shop/repo/OrderRepository.java");
            Files.writeString(repo, Files.readString(repo).replace("Optional<Order> findById(String id);", "Order findById(String id);"));
            v4 = commit(git, "shop v4");
        }
        User u = users.save(User.builder().email("inc@example.com").username("inc").passwordHash("x").displayName("inc").build());
        Repository a = repository(u, origin, "incremental");

        try (Git gitA = workspaceManager.cloneOrFetchRepository(a.getId(), a.getCloneUrl(), "main", null)) {
            assertTrue(commitAnalysis.ensureAnalyzed(gitA, a.getId(), v1.name()));
            assertTrue(commitAnalysis.ensureAnalyzed(gitA, a.getId(), v2.name()));
            var p3 = planner.plan(gitA.getRepository(), a.getId(), v3.name()).orElseThrow();
            assertEquals(v2.name(), p3.baseCommit());
            assertTrue(p3.copy().contains("src/main/java/com/shop/payment/PaymentGateway.java"), "unaffected files are reused: " + p3);
            assertTrue(p3.parse().contains("src/main/java/com/shop/domain/Order.java"), "same package as a new file: " + p3);
            assertTrue(p3.parse().containsAll(List.of("src/main/java/com/shop/web/OrderController.java",
                    "src/main/java/com/shop/util/Texts.java")), p3.toString());
            assertTrue(commitAnalysis.ensureAnalyzed(gitA, a.getId(), v3.name()));
            // rows of an older analyser version are never carried forward: with v3 marked as such, v4 builds on v2
            jdbc.update("UPDATE source_files SET analyzer_version = 0 WHERE repository_id = ? AND commit_sha = ?", a.getId(), v3.name());
            assertEquals(v2.name(), planner.plan(gitA.getRepository(), a.getId(), v4.name()).orElseThrow().baseCommit());
            jdbc.update("UPDATE source_files SET analyzer_version = ? WHERE repository_id = ? AND commit_sha = ?",
                    com.changeimpact.parser.service.CodeAnalysisService.ANALYZER_VERSION, a.getId(), v3.name());
            var p4 = planner.plan(gitA.getRepository(), a.getId(), v4.name()).orElseThrow();
            assertEquals(v3.name(), p4.baseCommit());
            assertTrue(p4.parse().containsAll(List.of("src/main/java/com/shop/repo/OrderRepository.java",
                    "src/main/java/com/shop/web/OrderController.java", "src/main/java/com/shop/service/OrderService.java")),
                    "files depending on the changed type are re-parsed: " + p4);
            assertTrue(p4.copy().contains("src/main/java/com/shop/domain/OrderStatus.java"), p4.toString());
            assertTrue(p4.copy().contains("src/main/java/com/shop/domain/Priority.java"), p4.toString());
            assertTrue(p4.parse().contains("src/main/java/com/shop/web/PriorityView.java"), p4.toString());
            assertTrue(commitAnalysis.ensureAnalyzed(gitA, a.getId(), v4.name()));
        }

        int compared = 0;
        for (RevCommit v : List.of(v2, v3, v4)) {
            Repository full = repository(u, origin, "full-" + v.name().substring(0, 7));
            try (Git g = workspaceManager.cloneOrFetchRepository(full.getId(), full.getCloneUrl(), "main", null)) {
                assertTrue(planner.plan(g.getRepository(), full.getId(), v.name()).isEmpty(), "nothing analysed yet → full parse");
                assertTrue(commitAnalysis.ensureAnalyzed(g, full.getId(), v.name()));
            }
            assertEquals(rows(FILES, full.getId(), v.name()), rows(FILES, a.getId(), v.name()), "source files of " + v.getShortMessage());
            assertEquals(rows(SYMBOLS, full.getId(), v.name()), rows(SYMBOLS, a.getId(), v.name()), "symbols of " + v.getShortMessage());
            List<String> expected = rows(DEPENDENCIES, full.getId(), v.name());
            assertEquals(expected, rows(DEPENDENCIES, a.getId(), v.name()), "dependencies of " + v.getShortMessage());
            assertTrue(expected.size() > 20);
            compared += expected.size();
        }
        assertTrue(compared > 100, "compared dependency rows: " + compared);
        // the v4 change is visible: the controller's orElseThrow() on Order no longer resolves
        assertTrue(rows(DEPENDENCIES, a.getId(), v4.name()).stream().anyMatch(r -> r.startsWith("com.shop.web.OrderController#find(String)|")
                && r.contains("orElseThrow") && r.contains("|UNRESOLVED|")));
        // the call into the enum links the first of the three same-named declarations (LOW's body, line 4)
        assertTrue(rows(DEPENDENCIES, a.getId(), v4.name()).stream().anyMatch(r -> r.startsWith("com.shop.web.PriorityView#high()|")
                && r.endsWith("|com.shop.domain.Priority#weight()@src/main/java/com/shop/domain/Priority.java:4")),
                String.join("\n", rows(DEPENDENCIES, a.getId(), v4.name()).stream().filter(r -> r.contains("Priority")).toList()));
    }

    private Repository repository(User owner, Path origin, String name) {
        return repositories.save(Repository.builder().owner(owner).fullName("local/" + name).name(name)
                .cloneUrl(origin.toUri().toString()).defaultBranch("main").build());
    }

    private static final String FILES = """
            SELECT relative_path || '|' || coalesce(package_name,'') || '|' || lines_of_code || '|' || is_test
              FROM source_files WHERE repository_id = ? AND commit_sha = ? ORDER BY 1""";
    private static final String SYMBOLS = """
            SELECT s.qualified_name || '|' || s.symbol_type || '|' || coalesce(p.qualified_name,'') || '|' || f.relative_path
                   || '|' || coalesce(s.start_line,0) || '-' || coalesce(s.end_line,0) || '|' || coalesce(s.content_hash,'')
                   || '|' || coalesce(s.complexity,0) || '|' || coalesce(s.visibility,'') || '|' || coalesce(s.annotation_text,'')
              FROM symbols s JOIN source_files f ON f.id = s.source_file_id LEFT JOIN symbols p ON p.id = s.parent_symbol_id
             WHERE s.repository_id = ? AND s.commit_sha = ? ORDER BY 1""";
    private static final String DEPENDENCIES = """
            SELECT src.qualified_name || '|' || coalesce(src.start_line,0) || '|' || d.target_qualified_name || '|' || d.dependency_type || '|'
                   || coalesce(d.source_location_line,0) || '|' || d.resolution_status || '|' || d.is_external || '|'
                   || coalesce(t.qualified_name,'') || '@' || coalesce(tf.relative_path,'') || ':' || coalesce(t.start_line,0)
              FROM dependencies d JOIN symbols src ON src.id = d.source_symbol_id LEFT JOIN symbols t ON t.id = d.target_symbol_id
                   LEFT JOIN source_files tf ON tf.id = t.source_file_id
             WHERE d.repository_id = ? AND d.commit_sha = ? ORDER BY 1""";

    private List<String> rows(String sql, UUID repositoryId, String commit) {
        return jdbc.queryForList(sql, String.class, repositoryId, commit);
    }

    private static RevCommit commit(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        git.add().addFilepattern(".").setUpdate(true).call();
        return git.commit().setMessage(message).setSign(false)
                .setAuthor("it", "it@example.com").setCommitter("it", "it@example.com").call();
    }

    private static Path fixture(String name) throws Exception {
        Path p = Paths.get(Objects.requireNonNull(IncrementalAnalysisIntegrationTest.class.getResource("/sample-projects/" + name)).toURI());
        assertTrue(Files.isDirectory(p));
        return p;
    }
}
