package com.changeimpact.defect;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.common.metrics.PlatformMetrics;
import com.changeimpact.github.service.GitHubAccountService;
import com.changeimpact.ingestion.workspace.WorkspaceManager;
import com.changeimpact.repository.entity.AnalysisJob;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.entity.User;
import com.changeimpact.repository.repository.AnalysisJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.function.Consumer;

/**
 * Trains and serves the historical defect-prediction prototype (P6-6). Training runs as a {@code DEFECT_MODEL} job
 * under the repository lock: dataset from Git history ({@link JitDatasetBuilder}), SZZ labels ({@link SzzLabeler}),
 * time-ordered evaluation and gate ({@link DefectModelEvaluator}). Only an {@code ACCEPTED} model contributes to
 * analyses, and then as a clearly labelled, history-based estimate next to the deterministic findings.
 */
@Service
@Slf4j
public class DefectModelService {

    /** Bump when features, labelling or the model change: stored models of older versions are not used. */
    public static final int MODEL_VERSION = 1;
    public static final int MAX_COMMITS = 5000;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AnalysisJobRepository jobs;
    private final WorkspaceManager workspaceManager;
    private final GitHubAccountService gitHubAccountService;
    private final AppProperties appProperties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TaskExecutor executor;

    public DefectModelService(AnalysisJobRepository jobs, WorkspaceManager workspaceManager,
                              GitHubAccountService gitHubAccountService, AppProperties appProperties, JdbcTemplate jdbc,
                              TransactionTemplate tx, @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.jobs = jobs;
        this.workspaceManager = workspaceManager;
        this.gitHubAccountService = gitHubAccountService;
        this.appProperties = appProperties;
        this.jdbc = jdbc;
        this.tx = tx;
        this.executor = executor;
    }

    /** Queues training; returns the job at once (poll {@code GET /api/v1/jobs/{id}}). */
    public AnalysisJob start(Repository repository, User owner) {
        AnalysisJob job = jobs.saveAndFlush(AnalysisJob.builder()
                .repository(repository).owner(owner)
                .jobType(AnalysisJob.JobType.DEFECT_MODEL)
                .payload(Map.of("maxCommits", MAX_COMMITS))
                .build());
        String token = repository.getConnectedAccount() != null
                ? gitHubAccountService.getDecryptedAccessToken(repository.getConnectedAccount()) : null;
        UUID jobId = job.getId(), repositoryId = repository.getId();
        String cloneUrl = repository.getCloneUrl(), branch = repository.getDefaultBranch();
        executor.execute(() -> run(jobId, repositoryId, cloneUrl, branch, token));
        return job;
    }

    void run(UUID jobId, UUID repositoryId, String cloneUrl, String branch, String token) {
        update(jobId, job -> { job.markRunning(); job.updateProgress("FETCHING", "Fetching repository...", 2); });
        try {
            Map<String, Object> summary = workspaceManager.withRepositoryLock(repositoryId,
                    appProperties.getIngestion().getJobTimeoutMinutes(), () -> {
                try (Git git = workspaceManager.cloneOrFetchRepository(repositoryId, cloneUrl, branch, token)) {
                    return train(jobId, repositoryId, git, workspaceManager.getHeadCommitSha(git));
                }
            });
            update(jobId, job -> {
                job.markCompleted(summary);
                job.updateProgress("COMPLETED", "Defect model: " + summary.get("status"), 100);
            });
            PlatformMetrics.job("defect-model", "completed");
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Defect model job {} failed: {}", jobId, message, e);
            update(jobId, job -> job.markFailed(message.length() > 1000 ? message.substring(0, 1000) : message));
            PlatformMetrics.job("defect-model", "failed");
        }
    }

    /** Builds the dataset, labels it, evaluates and stores the outcome. Package-visible for tests. */
    Map<String, Object> train(UUID jobId, UUID repositoryId, Git git, String head) throws Exception {
        long t0 = System.currentTimeMillis();
        if (jobId != null) update(jobId, j -> j.updateProgress("DATASET", "Computing change features from history...", 10));
        var samples = new JitDatasetBuilder().build(git.getRepository(), head, MAX_COMMITS);
        long t1 = System.currentTimeMillis();
        if (jobId != null) update(jobId, j -> j.updateProgress("LABELING",
                "Tracing " + samples.stream().filter(JitDatasetBuilder.Sample::fix).count() + " bug fixes (SZZ)...", 40));
        var szz = new SzzLabeler().label(git, samples);
        long t2 = System.currentTimeMillis();
        if (jobId != null) update(jobId, j -> j.updateProgress("TRAINING", "Time-ordered evaluation...", 85));
        var outcome = new DefectModelEvaluator().evaluate(samples, szz.links());

        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("commits", samples.size());
        dataset.put("maxCommits", MAX_COMMITS);
        dataset.put("from", samples.isEmpty() ? null : java.time.Instant.ofEpochMilli(samples.get(0).time()).toString());
        dataset.put("to", samples.isEmpty() ? null : java.time.Instant.ofEpochMilli(samples.get(samples.size() - 1).time()).toString());
        dataset.put("fixes", szz.fixes());
        dataset.put("tracedFixes", szz.tracedFixes());
        dataset.put("untraceableFixes", szz.untraceableFixes());
        dataset.put("bugIntroducingCommits", szz.links().stream().map(SzzLabeler.Link::introducing).distinct().count());
        dataset.put("features", JitDatasetBuilder.FEATURES);
        dataset.put("datasetMs", t1 - t0);
        dataset.put("labelingMs", t2 - t1);

        jdbc.update("""
                INSERT INTO defect_models (repository_id, status, reason, head_commit, model_version, dataset, evaluation,
                                           model, threshold, predictions, trained_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, NOW())
                ON CONFLICT (repository_id) DO UPDATE SET status = EXCLUDED.status, reason = EXCLUDED.reason,
                    head_commit = EXCLUDED.head_commit, model_version = EXCLUDED.model_version, dataset = EXCLUDED.dataset,
                    evaluation = EXCLUDED.evaluation, model = EXCLUDED.model, threshold = EXCLUDED.threshold,
                    predictions = EXCLUDED.predictions, trained_at = NOW()""",
                repositoryId, outcome.status().name(), outcome.reason(), head, MODEL_VERSION,
                JSON.writeValueAsString(dataset), JSON.writeValueAsString(outcome.evaluation()),
                outcome.finalModel() == null ? null : JSON.writeValueAsString(outcome.finalModel().toMap(JitDatasetBuilder.FEATURES)),
                outcome.finalModel() == null ? null : outcome.threshold(),
                JSON.writeValueAsString(outcome.predictions()));
        log.info("Defect model for repository {}: {} — {} ({} commits, {} fixes, {} traced; dataset {} ms, SZZ {} ms)",
                repositoryId, outcome.status(), outcome.reason(), samples.size(), szz.fixes(), szz.tracedFixes(), t1 - t0, t2 - t1);
        return Map.of("status", outcome.status().name(), "reason", outcome.reason(), "commits", samples.size());
    }

    /** Stored model and evaluation of a repository, if trained. */
    public Optional<Map<String, Object>> get(UUID repositoryId) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT status, reason, head_commit, model_version, dataset, evaluation, model, threshold, predictions, trained_at
                  FROM defect_models WHERE repository_id = ?""", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", rs.getString(1));
            m.put("reason", rs.getString(2));
            m.put("headCommit", rs.getString(3));
            m.put("current", rs.getInt(4) == MODEL_VERSION);
            m.put("dataset", read(rs.getString(5)));
            m.put("evaluation", read(rs.getString(6)));
            m.put("model", rs.getString(7) == null ? null : read(rs.getString(7)));
            m.put("threshold", rs.getObject(8));
            m.put("predictions", JSON.readValue(rs.getString(9), new TypeReference<List<Map<String, Object>>>() {}));
            m.put("trainedAt", rs.getTimestamp(10).toInstant().toString());
            return m;
        }, repositoryId);
        return rows.stream().findFirst();
    }

    /**
     * The defect model's view of the commits in {@code base..head}, for an analysis. Only an accepted, current model
     * is used; commits newer than its training are reported as not scored.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> forRange(UUID repositoryId, Git git, String base, String head) {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<Map<String, Object>> stored = get(repositoryId);
        if (stored.isEmpty()) {
            out.put("status", "NOT_TRAINED");
            return out;
        }
        Map<String, Object> m = stored.get();
        out.put("status", Boolean.TRUE.equals(m.get("current")) ? m.get("status") : "OUTDATED");
        out.put("reason", m.get("reason"));
        out.put("trainedAt", m.get("trainedAt"));
        if (!"ACCEPTED".equals(out.get("status"))) return out;

        Map<String, Map<String, Object>> bySha = new HashMap<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) m.get("predictions")) bySha.put((String) p.get("sha"), p);
        List<Map<String, Object>> scored = new ArrayList<>();
        int newer = 0, notCode = 0, total = 0;
        try (RevWalk walk = new RevWalk(git.getRepository());
             RevWalk ancestry = new RevWalk(git.getRepository())) {
            RevCommit trainedHead = ancestry.parseCommit(ObjectId.fromString((String) m.get("headCommit")));
            walk.markStart(walk.parseCommit(ObjectId.fromString(head)));
            walk.markUninteresting(walk.parseCommit(ObjectId.fromString(base)));
            for (RevCommit c : walk) {
                if (c.getParentCount() > 1) continue;
                if (++total > 200) break;
                Map<String, Object> p = bySha.get(c.getName());
                if (p == null) {
                    // known to the model but not a sample: it changes no source code (or is older than the scored window)
                    if (ancestry.isMergedInto(ancestry.parseCommit(c.getId()), trainedHead)) notCode++;
                    else newer++;
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>(p);
                row.put("message", c.getShortMessage()); // current subject (stored one may be truncated)
                scored.add(row);
            }
        } catch (Exception e) {
            log.debug("Defect model range lookup failed: {}", e.getMessage());
        }
        scored.sort(Comparator.comparingDouble(p -> -((Number) p.get("probability")).doubleValue()));
        out.put("commits", scored);
        out.put("notScored", newer);           // newer than the model's training: retrain to score them
        out.put("withoutCodeChanges", notCode); // documentation/build-only commits are not scored
        out.put("threshold", m.get("threshold"));
        Map<?, ?> model = (Map<?, ?>) ((Map<?, ?>) m.get("evaluation")).get("model");
        out.put("testAuc", model == null ? null : model.get("auc"));
        return out;
    }

    private static Map<String, Object> read(String json) {
        return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    private void update(UUID jobId, Consumer<AnalysisJob> change) {
        tx.executeWithoutResult(s -> jobs.findById(jobId).ifPresent(job -> {
            change.accept(job);
            jobs.save(job);
        }));
    }
}
