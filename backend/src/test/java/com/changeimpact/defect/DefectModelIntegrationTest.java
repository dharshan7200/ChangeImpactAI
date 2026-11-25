package com.changeimpact.defect;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
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
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.entity.User;
import com.changeimpact.repository.repository.RepositoryRepository;
import com.changeimpact.repository.repository.UserRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The whole defect-model pipeline on a generated repository with a planted, known pattern: newcomers' commits
 * introduce lines that a later "Fix ..." commit corrects, while change size is unrelated to defects. The model must
 * find the pattern (beat the churn baseline), be stored, and be used for the commits of an analysis range.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Integration: historical defect model (SZZ labels, time-ordered evaluation, storage, analysis lookup)")
class DefectModelIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine").asCompatibleSubstituteFor("rabbitmq"));

    @TempDir static Path tmp;

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

    @Autowired DefectModelService service;
    @Autowired RepositoryRepository repositories;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private long clock = Instant.parse("2023-01-01T00:00:00Z").getEpochSecond();

    @Test
    @DisplayName("planted pattern is learned, beats churn, is stored and scores the commits of a range")
    @SuppressWarnings("unchecked")
    void trainStoreAndServe() throws Exception {
        Path dir = tmp.resolve("origin");
        Random random = new Random(1);
        Map<String, List<String>> files = new TreeMap<>();
        Deque<String[]> pendingFixes = new ArrayDeque<>(); // {file, bug line}
        List<String> shas = new ArrayList<>();
        Set<String> novice = new HashSet<>();
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            for (int i = 0; i < 640; i++) {
                boolean newcomer = i % 8 < 3;
                String author = newcomer ? "new" + (i / 8) : "vet" + (i % 3);
                String file = "mod" + (i % 6) + "/src/F" + random.nextInt(30) + ".java";
                List<String> lines = files.computeIfAbsent(file, f -> new ArrayList<>());
                int added = 1 + random.nextInt(25);                    // size independent of defects
                for (int k = 0; k < added; k++) lines.add("  int v" + i + "_" + k + " = " + k + ";");
                boolean buggy = random.nextDouble() < (newcomer ? 0.7 : 0.03);
                if (buggy) {
                    String bug = "  int bug" + i + " = 0;";
                    lines.add(bug);
                    pendingFixes.add(new String[]{file, bug});
                }
                String sha = commit(git, dir, author, "Change " + i, files);
                shas.add(sha);
                if (newcomer) novice.add(sha);
                if (pendingFixes.size() >= 3) {                       // fixes arrive a few commits later
                    String[] f = pendingFixes.poll();
                    List<String> l = files.get(f[0]);
                    l.set(l.indexOf(f[1]), f[1].replace("= 0;", "= 1;"));
                    commit(git, dir, "vet0", "Fix wrong value in " + f[0], files);
                }
            }
        }
        User u = users.save(User.builder().email("ml@example.com").username("ml").passwordHash("x").displayName("ml").build());
        Repository repo = repositories.save(Repository.builder().owner(u).fullName("local/ml").name("ml")
                .cloneUrl(dir.toUri().toString()).defaultBranch("main").build());

        try (Git git = Git.open(dir.toFile())) {
            String head = git.getRepository().resolve("HEAD").getName();
            Map<String, Object> summary = service.train(null, repo.getId(), git, head);
            assertEquals("ACCEPTED", summary.get("status"), summary.toString());

            Map<String, Object> stored = service.get(repo.getId()).orElseThrow();
            Map<String, Object> ev = (Map<String, Object>) stored.get("evaluation");
            double auc = ((Number) ((Map<String, Object>) ev.get("model")).get("auc")).doubleValue();
            double churnAuc = ((Number) ((Map<String, Object>) ev.get("churnBaseline")).get("auc")).doubleValue();
            assertTrue(auc > 0.8, "model finds the planted pattern: " + auc);
            assertTrue(churnAuc < 0.65, "size is not the signal here: " + churnAuc);
            Map<String, Object> dataset = (Map<String, Object>) stored.get("dataset");
            assertTrue(((Number) dataset.get("tracedFixes")).intValue() > 100, dataset.toString());
            List<Map<String, Object>> predictions = (List<Map<String, Object>>) stored.get("predictions");
            assertFalse(predictions.isEmpty());
            assertNotNull(stored.get("model"));

            // the analysis view of the last 40 commits: scored, newcomers ranked above veterans
            String base = shas.get(shas.size() - 40);
            Map<String, Object> view = service.forRange(repo.getId(), git, base, head);
            assertEquals("ACCEPTED", view.get("status"));
            List<Map<String, Object>> commits = (List<Map<String, Object>>) view.get("commits");
            assertTrue(commits.size() >= 39, "commits of the range are scored: " + commits.size());
            double novMean = commits.stream().filter(c -> novice.contains(c.get("sha"))).mapToDouble(c -> ((Number) c.get("probability")).doubleValue()).average().orElseThrow();
            double vetMean = commits.stream().filter(c -> !novice.contains(c.get("sha"))).mapToDouble(c -> ((Number) c.get("probability")).doubleValue()).average().orElseThrow();
            assertTrue(novMean > 2 * vetMean, "newcomer commits rank higher: " + novMean + " vs " + vetMean);
            assertTrue(((List<?>) commits.get(0).get("drivers")).size() == 4, "each score is explained");
        }

        // a repository whose history has no usable labels is stored, but not used
        jdbc.update("UPDATE defect_models SET model_version = 0 WHERE repository_id = ?", repo.getId());
        try (Git git = Git.open(dir.toFile())) {
            String head = git.getRepository().resolve("HEAD").getName();
            assertEquals("OUTDATED", service.forRange(repo.getId(), git, shas.get(0), head).get("status"),
                    "a model from an older pipeline version is not used");
        }
    }

    private String commit(Git git, Path dir, String author, String message, Map<String, List<String>> files) throws Exception {
        for (var e : files.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, "class X {\n" + String.join("\n", e.getValue()) + "\n}\n");
        }
        git.add().addFilepattern(".").call();
        clock += 3600;
        PersonIdent who = new PersonIdent(author, author + "@example.com", Instant.ofEpochSecond(clock), ZoneOffset.UTC);
        return git.commit().setSign(false).setMessage(message).setAuthor(who).setCommitter(who).call().getName();
    }
}
