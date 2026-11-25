package com.changeimpact.architecture.twin;

import com.changeimpact.analysis.service.CommitAnalysisService;
import com.changeimpact.common.config.AppProperties;
import com.changeimpact.common.metrics.PlatformMetrics;
import com.changeimpact.github.service.GitHubAccountService;
import com.changeimpact.history.service.HistoryService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Gives the architecture twin a history: analyses up to {@link #MAX_COMMITS} commits spread evenly over the default
 * branch's first-parent history (oldest first, so each one is planned incrementally from its predecessor) and builds
 * their snapshots. Runs as an {@code ARCHITECTURE_HISTORY} job under the repository lock; progress and failures are on
 * the job like for indexing.
 */
@Service
@Slf4j
public class ArchitectureHistoryService {

    public static final int MAX_COMMITS = 20;

    private final AnalysisJobRepository jobs;
    private final WorkspaceManager workspaceManager;
    private final GitHubAccountService gitHubAccountService;
    private final HistoryService historyService;
    private final CommitAnalysisService commitAnalysisService;
    private final ArchitectureTwinService twinService;
    private final AppProperties appProperties;
    private final TransactionTemplate tx;
    private final TaskExecutor executor;

    public ArchitectureHistoryService(AnalysisJobRepository jobs, WorkspaceManager workspaceManager,
                                      GitHubAccountService gitHubAccountService, HistoryService historyService,
                                      CommitAnalysisService commitAnalysisService, ArchitectureTwinService twinService,
                                      AppProperties appProperties, TransactionTemplate tx,
                                      @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.jobs = jobs;
        this.workspaceManager = workspaceManager;
        this.gitHubAccountService = gitHubAccountService;
        this.historyService = historyService;
        this.commitAnalysisService = commitAnalysisService;
        this.twinService = twinService;
        this.appProperties = appProperties;
        this.tx = tx;
        this.executor = executor;
    }

    /** Queues the job; returns it at once (poll {@code GET /api/v1/jobs/{id}}). */
    public AnalysisJob start(Repository repository, User owner, int commits) {
        int count = Math.max(2, Math.min(MAX_COMMITS, commits));
        AnalysisJob job = jobs.saveAndFlush(AnalysisJob.builder()
                .repository(repository).owner(owner)
                .jobType(AnalysisJob.JobType.ARCHITECTURE_HISTORY)
                .payload(Map.of("commits", count))
                .build());
        String token = repository.getConnectedAccount() != null
                ? gitHubAccountService.getDecryptedAccessToken(repository.getConnectedAccount()) : null;
        UUID jobId = job.getId(), repositoryId = repository.getId();
        String cloneUrl = repository.getCloneUrl(), branch = repository.getDefaultBranch();
        executor.execute(() -> run(jobId, repositoryId, cloneUrl, branch, token, count));
        return job;
    }

    void run(UUID jobId, UUID repositoryId, String cloneUrl, String branch, String token, int count) {
        update(jobId, job -> { job.markRunning(); job.updateProgress("FETCHING", "Fetching repository...", 2); });
        try {
            List<Map<String, Object>> done = workspaceManager.withRepositoryLock(repositoryId,
                    appProperties.getIngestion().getJobTimeoutMinutes(), () -> {
                try (Git git = workspaceManager.cloneOrFetchRepository(repositoryId, cloneUrl, branch, token)) {
                    String head = workspaceManager.getHeadCommitSha(git);
                    int depth = appProperties.getHistory().getMaxCommits();
                    historyService.scan(git, repositoryId, head, depth);
                    List<String> selected = sample(firstParentHistory(git, head, depth), count);
                    List<Map<String, Object>> result = new ArrayList<>();
                    for (int i = 0; i < selected.size(); i++) {
                        String sha = selected.get(i);
                        int percent = 5 + (int) (90.0 * i / selected.size());
                        update(jobId, job -> job.updateProgress("ANALYZING",
                                "Commit " + sha.substring(0, 7) + " (" + (result.size() + 1) + " of " + selected.size() + ")", percent));
                        long start = System.currentTimeMillis();
                        // sampled commits can lie beyond the scanned history window: record their date and message
                        historyService.scan(git, repositoryId, sha, 1);
                        boolean analysed = commitAnalysisService.ensureAnalyzed(git, repositoryId, sha);
                        var snapshot = twinService.snapshot(repositoryId, sha).orElseThrow();
                        result.add(Map.of("commit", sha, "analysed", analysed, "packages", snapshot.summary().packages(),
                                "ms", System.currentTimeMillis() - start));
                    }
                    return result;
                }
            });
            update(jobId, job -> {
                job.markCompleted(Map.of("commits", done));
                job.updateProgress("COMPLETED", "Architecture history of " + done.size() + " commits ready", 100);
            });
            PlatformMetrics.job("architecture", "completed");
            log.info("Architecture history job {} completed: {} commits", jobId, done.size());
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Architecture history job {} failed: {}", jobId, message, e);
            update(jobId, job -> job.markFailed(message.length() > 1000 ? message.substring(0, 1000) : message));
            PlatformMetrics.job("architecture", "failed");
        }
    }

    /** First-parent history from {@code head}, newest first, at most {@code limit} commits. */
    static List<String> firstParentHistory(Git git, String head, int limit) throws Exception {
        List<String> shas = new ArrayList<>();
        try (RevWalk walk = new RevWalk(git.getRepository())) {
            walk.setFirstParent(true);
            walk.markStart(walk.parseCommit(ObjectId.fromString(head)));
            for (RevCommit c : walk) {
                shas.add(c.getName());
                if (shas.size() >= limit) break;
            }
        }
        return shas;
    }

    /** {@code count} commits spread evenly over the history (newest and oldest included), returned oldest first. */
    static List<String> sample(List<String> newestFirst, int count) {
        int n = newestFirst.size();
        if (n <= count) {
            List<String> all = new ArrayList<>(newestFirst);
            Collections.reverse(all);
            return all;
        }
        TreeSet<Integer> picks = new TreeSet<>(Comparator.reverseOrder());
        for (int i = 0; i < count; i++) picks.add((int) Math.round(i * (n - 1) / (double) (count - 1)));
        List<String> out = new ArrayList<>();
        for (int index : picks) out.add(newestFirst.get(index));
        return out;
    }

    private void update(UUID jobId, java.util.function.Consumer<AnalysisJob> change) {
        tx.executeWithoutResult(s -> jobs.findById(jobId).ifPresent(job -> {
            change.accept(job);
            jobs.save(job);
        }));
    }
}
