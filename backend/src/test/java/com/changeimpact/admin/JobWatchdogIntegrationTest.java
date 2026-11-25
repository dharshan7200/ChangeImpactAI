package com.changeimpact.admin;

import com.changeimpact.analysis.entity.ChangeAnalysis;
import com.changeimpact.analysis.repository.ChangeAnalysisRepository;
import com.changeimpact.analysis.service.ChangeAnalysisEvent;
import com.changeimpact.repository.entity.AnalysisJob;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.entity.User;
import com.changeimpact.repository.repository.AnalysisJobRepository;
import com.changeimpact.repository.repository.RepositoryRepository;
import com.changeimpact.repository.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@RecordApplicationEvents
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Integration: job watchdog (timeouts of running and queued jobs)")
class JobWatchdogIntegrationTest {

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
        registry.add("app.ingestion.job-timeout-minutes", () -> "30");
        registry.add("app.ingestion.queued-timeout-minutes", () -> "720");
    }

    @Autowired JobWatchdog watchdog;
    @Autowired UserRepository users;
    @Autowired RepositoryRepository repositories;
    @Autowired AnalysisJobRepository jobs;
    @Autowired ChangeAnalysisRepository analyses;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEvents events;

    private AnalysisJob job(Repository repo, User owner, AnalysisJob.JobType type, AnalysisJob.Status status, String age) {
        AnalysisJob j = jobs.save(AnalysisJob.builder().repository(repo).owner(owner).jobType(type).status(status)
                .idempotencyKey(UUID.randomUUID().toString()).payload(Map.of()).build());
        jdbc.update("UPDATE analysis_jobs SET created_at = now() - CAST(? AS interval) WHERE id = ?", age, j.getId());
        if (status == AnalysisJob.Status.RUNNING) {
            jdbc.update("UPDATE analysis_jobs SET started_at = now() - CAST(? AS interval) WHERE id = ?", age, j.getId());
        }
        return j;
    }

    @Test
    @DisplayName("stuck RUNNING and lost QUEUED jobs fail; their analyses follow and emit FAILED events; fresh jobs are untouched")
    void sweep() {
        User u = users.save(User.builder().email("w@example.com").username("watchdog").passwordHash("x").displayName("w").build());
        Repository repo = repositories.save(Repository.builder().owner(u).fullName("w/repo").name("repo")
                .cloneUrl("https://github.com/w/repo.git").indexingStatus(Repository.IndexingStatus.INDEXING).build());
        AnalysisJob stuck = job(repo, u, AnalysisJob.JobType.CHANGE_ANALYSIS, AnalysisJob.Status.RUNNING, "2 hours");
        AnalysisJob lost = job(repo, u, AnalysisJob.JobType.CHANGE_ANALYSIS, AnalysisJob.Status.QUEUED, "2 days");
        AnalysisJob fresh = job(repo, u, AnalysisJob.JobType.CHANGE_ANALYSIS, AnalysisJob.Status.RUNNING, "5 minutes");
        ChangeAnalysis a = ChangeAnalysis.builder().repositoryId(repo.getId()).ownerId(u.getId()).jobId(stuck.getId()).baseRef("a").headRef("b").build();
        a.setStatus(ChangeAnalysis.Status.RUNNING);
        UUID stuckAnalysis = analyses.save(a).getId();
        ChangeAnalysis b = ChangeAnalysis.builder().repositoryId(repo.getId()).ownerId(u.getId()).jobId(fresh.getId()).baseRef("a").headRef("c").build();
        b.setStatus(ChangeAnalysis.Status.RUNNING);
        UUID freshAnalysis = analyses.save(b).getId();

        Map<String, Object> result = watchdog.sweep();

        assertEquals(1, result.get("runningJobsTimedOut"));
        assertEquals(1, result.get("queuedJobsTimedOut"));
        assertEquals(1, result.get("analysesFailed"));
        assertEquals(1, result.get("repositoriesReset"), "INDEXING without an active indexing job");
        assertEquals(AnalysisJob.Status.FAILED, jobs.findById(stuck.getId()).orElseThrow().getStatus());
        assertEquals(AnalysisJob.Status.FAILED, jobs.findById(lost.getId()).orElseThrow().getStatus());
        assertEquals(AnalysisJob.Status.RUNNING, jobs.findById(fresh.getId()).orElseThrow().getStatus());
        ChangeAnalysis failed = analyses.findById(stuckAnalysis).orElseThrow();
        assertEquals(ChangeAnalysis.Status.FAILED, failed.getStatus());
        assertTrue(failed.getErrorMessage().startsWith("Timed out: running longer than 30 minutes"), failed.getErrorMessage());
        assertEquals(ChangeAnalysis.Status.RUNNING, analyses.findById(freshAnalysis).orElseThrow().getStatus());
        assertEquals(1, events.stream(ChangeAnalysisEvent.class)
                .filter(e -> e.analysisId().equals(stuckAnalysis) && e.phase() == ChangeAnalysisEvent.Phase.FAILED).count());
        assertEquals(Map.of("runningJobsTimedOut", 0, "queuedJobsTimedOut", 0, "analysesFailed", 0, "indexesFailed", 0,
                "repositoriesReset", 0), watchdog.sweep(), "idempotent");
    }
}
