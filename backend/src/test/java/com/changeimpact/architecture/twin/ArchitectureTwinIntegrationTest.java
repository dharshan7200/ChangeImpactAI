package com.changeimpact.architecture.twin;

import com.changeimpact.analysis.service.CommitAnalysisService;
import com.changeimpact.architecture.twin.TwinModel.*;
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
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The twin on two commits of the shop fixture, the second of which adds a domain → service dependency and thereby a
 * package cycle: snapshot invariants (coupling = edges, dependencies-first order), persistence, and the comparison.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Integration: architecture digital twin (snapshot, cycle drift, comparison)")
class ArchitectureTwinIntegrationTest {

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
    }

    @Autowired CommitAnalysisService commitAnalysis;
    @Autowired WorkspaceManager workspaceManager;
    @Autowired ArchitectureTwinService twin;
    @Autowired RepositoryRepository repositories;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("a new domain → service dependency shows up as a new package cycle and drift findings")
    void snapshotAndDrift() throws Exception {
        Path origin = tmp.resolve("origin");
        RevCommit v1, v2;
        try (Git git = Git.init().setDirectory(origin.toFile()).setInitialBranch("main").call()) {
            FileUtils.copyDirectory(fixture("shop-v2").toFile(), origin.toFile());
            v1 = commit(git, "shop");
            Files.writeString(origin.resolve("src/main/java/com/shop/domain/OrderAudit.java"), """
                    package com.shop.domain;

                    import com.shop.service.OrderService;

                    public class OrderAudit {
                        private OrderService service;

                        public OrderService service() { return service; }
                    }
                    """);
            v2 = commit(git, "domain now depends on service");
        }
        User u = users.save(User.builder().email("twin@example.com").username("twin").passwordHash("x").displayName("twin").build());
        Repository repo = repositories.save(Repository.builder().owner(u).fullName("local/twin").name("twin")
                .cloneUrl(origin.toUri().toString()).defaultBranch("main").build());
        try (Git g = workspaceManager.cloneOrFetchRepository(repo.getId(), repo.getCloneUrl(), "main", null)) {
            assertTrue(commitAnalysis.ensureAnalyzed(g, repo.getId(), v1.name()));
            assertTrue(commitAnalysis.ensureAnalyzed(g, repo.getId(), v2.name()));
        }

        Snapshot before = twin.snapshot(repo.getId(), v1.name()).orElseThrow();
        Snapshot after = twin.snapshot(repo.getId(), v2.name()).orElseThrow();
        assertInvariants(before);
        assertInvariants(after);
        assertTrue(before.packages().stream().anyMatch(p -> p.name().equals("com.shop.domain")), before.packages().toString());
        assertTrue(before.cycles().isEmpty(), "no package cycle before: " + before.cycles());
        assertTrue(after.cycles().stream().anyMatch(c -> c.contains("com.shop.domain") && c.contains("com.shop.service")),
                "domain ↔ service cycle after: " + after.cycles());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM architecture_snapshots WHERE repository_id = ? AND commit_sha = ?",
                Integer.class, repo.getId(), v2.name()), "snapshot is stored");
        assertEquals(after, twin.snapshot(repo.getId(), v2.name()).orElseThrow(), "stored snapshot reads back identically");

        Comparison c = twin.compare(repo.getId(), before, after);
        assertTrue(c.addedEdges().stream().anyMatch(e -> e.source().equals("com.shop.domain") && e.target().equals("com.shop.service")),
                c.addedEdges().toString());
        assertFalse(c.newCycles().isEmpty());
        assertTrue(c.findings().get(0).startsWith("New package cycle:"), c.findings().toString());
        assertTrue(c.packageDeltas().stream().anyMatch(d -> d.name().equals("com.shop.domain") && d.ceAfter() == d.ceBefore() + 1),
                c.packageDeltas().toString());
        assertEquals(List.of(), twin.compare(repo.getId(), after, after).addedEdges());

        // trend lists both commits; hotspots cover every production file
        assertEquals(2, twin.trend(repo.getId()).size());
        Hotspots h = twin.hotspots(repo.getId(), v2.name()).orElseThrow();
        assertEquals(after.summary().files(), h.files().size());
        assertTrue(h.files().stream().allMatch(f -> f.hotspotScore() >= 0 && f.hotspotScore() <= 1));
        assertTrue(twin.snapshot(repo.getId(), "0".repeat(40)).isEmpty(), "unknown commit");
    }

    /** Ca/Ce equal the package edges; every dependency outside a cycle points to a package placed earlier. */
    private static void assertInvariants(Snapshot s) {
        Map<String, PackageNode> byName = s.packages().stream().collect(Collectors.toMap(PackageNode::name, p -> p));
        for (PackageNode p : s.packages()) {
            assertEquals(s.edges().stream().filter(e -> e.source().equals(p.name())).count(), p.ce(), "Ce of " + p.name());
            assertEquals(s.edges().stream().filter(e -> e.target().equals(p.name())).count(), p.ca(), "Ca of " + p.name());
        }
        for (PackageEdge e : s.edges()) {
            assertNotEquals(e.source(), e.target());
            if (!e.inCycle()) {
                assertTrue(byName.get(e.target()).order() < byName.get(e.source()).order(), "dependencies first: " + e);
            }
        }
        assertEquals(s.packages().size(), s.packages().stream().map(PackageNode::order).distinct().count(), "order is a permutation");
        assertEquals(s.summary().packageEdges(), s.edges().size());
    }

    private static RevCommit commit(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(message).setSign(false)
                .setAuthor("it", "it@example.com").setCommitter("it", "it@example.com").call();
    }

    private static Path fixture(String name) throws Exception {
        return Paths.get(Objects.requireNonNull(ArchitectureTwinIntegrationTest.class.getResource("/sample-projects/" + name)).toURI());
    }
}
