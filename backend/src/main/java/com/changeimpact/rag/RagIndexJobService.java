package com.changeimpact.rag;

import com.changeimpact.ai.GeminiClient;
import com.changeimpact.common.config.AppProperties;
import com.changeimpact.common.exception.BusinessException;
import com.changeimpact.common.metrics.PlatformMetrics;
import com.changeimpact.github.service.GitHubAccountService;
import com.changeimpact.ingestion.workspace.WorkspaceManager;
import com.changeimpact.repository.entity.AnalysisJob;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.entity.User;
import com.changeimpact.repository.repository.AnalysisJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** {@code RAG_INDEX} job: chunks + embeddings of a repository's last indexed commit, under the repository lock. */
@Service
@Slf4j
public class RagIndexJobService {

    private final AnalysisJobRepository jobs;
    private final WorkspaceManager workspaceManager;
    private final GitHubAccountService accounts;
    private final RagService rag;
    private final GeminiClient gemini;
    private final AppProperties appProperties;
    private final TransactionTemplate tx;
    private final TaskExecutor executor;

    public RagIndexJobService(AnalysisJobRepository jobs, WorkspaceManager workspaceManager, GitHubAccountService accounts,
                              RagService rag, GeminiClient gemini, AppProperties appProperties, TransactionTemplate tx,
                              @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.jobs = jobs;
        this.workspaceManager = workspaceManager;
        this.accounts = accounts;
        this.rag = rag;
        this.gemini = gemini;
        this.appProperties = appProperties;
        this.tx = tx;
        this.executor = executor;
    }

    public AnalysisJob start(Repository repository, User owner) {
        if (!gemini.enabled()) throw new BusinessException("AI_NOT_CONFIGURED", "AI features are not configured (GEMINI_API_KEY)");
        if (repository.getLastIndexedCommit() == null) throw new BusinessException("NOT_INDEXED", "Index the repository first");
        AnalysisJob job = jobs.saveAndFlush(AnalysisJob.builder().repository(repository).owner(owner)
                .jobType(AnalysisJob.JobType.RAG_INDEX).payload(Map.of("commit", repository.getLastIndexedCommit())).build());
        String token = repository.getConnectedAccount() != null ? accounts.getDecryptedAccessToken(repository.getConnectedAccount()) : null;
        UUID jobId = job.getId(), repoId = repository.getId();
        String url = repository.getCloneUrl(), branch = repository.getDefaultBranch(), commit = repository.getLastIndexedCommit();
        executor.execute(() -> run(jobId, repoId, url, branch, token, commit));
        return job;
    }

    void run(UUID jobId, UUID repoId, String url, String branch, String token, String commit) {
        update(jobId, j -> { j.markRunning(); j.updateProgress("CHUNKING", "Reading code of " + commit.substring(0, 7) + "...", 5); });
        try {
            RagService.IndexStats stats = workspaceManager.withRepositoryLock(repoId, appProperties.getIngestion().getJobTimeoutMinutes(), () -> {
                try (Git git = workspaceManager.cloneOrFetchRepository(repoId, url, branch, token)) {
                    update(jobId, j -> j.updateProgress("EMBEDDING", "Embedding code chunks (" + gemini.embeddingModel() + ")...", 30));
                    return rag.index(git.getRepository(), repoId, commit);
                }
            });
            update(jobId, j -> {
                j.markCompleted(Map.of("chunks", stats.chunks(), "created", stats.created(), "embedded", stats.embedded(),
                        "embeddingsReused", stats.embeddingsReused(), "ms", stats.ms()));
                j.updateProgress("COMPLETED", stats.chunks() + " code chunks searchable", 100);
            });
            PlatformMetrics.job("rag-index", "completed");
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("RAG index job {} failed: {}", jobId, m, e);
            update(jobId, j -> j.markFailed(m.length() > 1000 ? m.substring(0, 1000) : m));
            PlatformMetrics.job("rag-index", "failed");
        }
    }

    private void update(UUID jobId, Consumer<AnalysisJob> change) {
        tx.executeWithoutResult(s -> jobs.findById(jobId).ifPresent(j -> { change.accept(j); jobs.save(j); }));
    }
}
