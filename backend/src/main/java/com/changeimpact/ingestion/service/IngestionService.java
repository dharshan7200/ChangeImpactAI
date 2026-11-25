package com.changeimpact.ingestion.service;

import com.changeimpact.architecture.repository.ArchitectureViolationRepository;
import com.changeimpact.architecture.service.ArchitectureRuleEngine;
import com.changeimpact.common.exception.IngestionException;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.github.service.GitHubAccountService;
import com.changeimpact.graph.service.DependencyGraphService;
import com.changeimpact.ingestion.workspace.WorkspaceManager;
import com.changeimpact.parser.service.CodeAnalysisService;
import com.changeimpact.repository.entity.AnalysisJob;
import com.changeimpact.repository.entity.Commit;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.entity.RepositoryBranch;
import com.changeimpact.repository.entity.RepositoryIndex;
import com.changeimpact.repository.repository.AnalysisJobRepository;
import com.changeimpact.repository.repository.CommitRepository;
import com.changeimpact.repository.repository.RepositoryBranchRepository;
import com.changeimpact.repository.repository.RepositoryIndexRepository;
import com.changeimpact.repository.repository.RepositoryRepository;
import com.changeimpact.symbols.repository.SourceFileRepository;
import com.changeimpact.symbols.repository.SymbolDependencyRepository;
import com.changeimpact.symbols.repository.SymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates repository cloning, Git inspection, AST analysis and status tracking
 * during repository ingestion.
 *
 * <p><b>Transaction design.</b> The job deliberately does <i>not</i> run inside one big
 * transaction. Every progress update, the analysis itself, and the final success/failure
 * bookkeeping each commit in their own short transaction ({@link TransactionTemplate}). This
 * guarantees that:
 * <ul>
 *   <li>the UI can observe intermediate stages (CLONING → SCANNING → PARSING → ...) while the job runs;</li>
 *   <li>a database error during analysis cannot roll back the FAILED status written afterwards
 *       (previously this caused {@code UnexpectedRollbackException} and a repository stuck in INDEXING);</li>
 *   <li>no database connection is held open during the slow network clone.</li>
 * </ul>
 *
 * <p><b>Re-indexing.</b> Analysis results are keyed by (repository, commit). Re-indexing an
 * already-indexed commit first deletes the previous results for that commit, so the operation is
 * idempotent instead of failing on the {@code source_files} unique constraint.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionService {

    private final RepositoryRepository repositoryRepository;
    private final RepositoryIndexRepository repositoryIndexRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final CommitRepository commitRepository;
    private final RepositoryBranchRepository repositoryBranchRepository;
    private final SourceFileRepository sourceFileRepository;
    private final SymbolRepository symbolRepository;
    private final SymbolDependencyRepository symbolDependencyRepository;
    private final com.changeimpact.analysis.service.IncrementalAnalysisPlanner incrementalPlanner;
    private final ArchitectureViolationRepository architectureViolationRepository;
    private final GitHubAccountService gitHubAccountService;
    private final WorkspaceManager workspaceManager;
    private final CodeAnalysisService codeAnalysisService;
    private final ArchitectureRuleEngine architectureRuleEngine;
    private final DependencyGraphService dependencyGraphService;
    private final TransactionTemplate transactionTemplate;
    private final com.changeimpact.history.service.HistoryService historyService;
    private final com.changeimpact.common.config.AppProperties appProperties;
    /** Receives stage durations of each finished job (metrics); no-op unless observability is wired. */
    private final java.util.function.BiConsumer<String, Map<String, Long>> stageTimings;

    /** Immutable data needed outside a transaction (no lazy JPA proxies). */
    private record JobContext(UUID repositoryIndexId, String fullName, String cloneUrl,
                              String branch, String accessToken) {}

    /**
     * Executes an indexing job end to end.
     *
     * @throws IngestionException when indexing fails; the failure has already been persisted on the
     *                            job, index and repository before the exception is thrown, so the
     *                            caller (the RabbitMQ consumer) only has to route the message.
     */
    public void executeIndexingJob(UUID jobId, UUID repositoryId, String branch) {
        log.info("Starting repository indexing job {} for repo {} (branch: {})", jobId, repositoryId, branch);

        JobContext ctx = transactionTemplate.execute(status -> startJob(jobId, repositoryId, branch));

        try {
            workspaceManager.withRepositoryLock(repositoryId, appProperties.getIngestion().getJobTimeoutMinutes(), () -> {
                runPipeline(jobId, repositoryId, ctx);
                return null;
            });
        } catch (Exception e) {
            log.error("Indexing failed for repository {}: {}", repositoryId, e.getMessage(), e);
            String message = rootMessage(e);
            transactionTemplate.executeWithoutResult(status ->
                    recordFailure(jobId, repositoryId, ctx.repositoryIndexId(), message));
            com.changeimpact.common.metrics.PlatformMetrics.job("indexing", "failed");
            throw (e instanceof IngestionException ie) ? ie : new IngestionException("Indexing failed: " + message, e);
        }
    }

    // ---------------------------------------------------------------------
    // Pipeline steps
    // ---------------------------------------------------------------------

    private JobContext startJob(UUID jobId, UUID repositoryId, String branch) {
        AnalysisJob job = analysisJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("AnalysisJob", jobId));
        Repository repository = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repositoryId));

        job.markRunning();
        job.updateProgress("CLONING", "Cloning repository workspace from GitHub...", 10);
        analysisJobRepository.save(job);

        String targetBranch = (branch != null && !branch.isBlank()) ? branch : repository.getDefaultBranch();
        String accessToken = repository.getConnectedAccount() != null
                ? gitHubAccountService.getDecryptedAccessToken(repository.getConnectedAccount())
                : null;

        RepositoryIndex repoIndex = repositoryIndexRepository.save(RepositoryIndex.builder()
                .repository(repository)
                .branchName(targetBranch)
                .commitSha("PENDING")
                .status(RepositoryIndex.Status.RUNNING)
                .startedAt(Instant.now())
                .build());

        return new JobContext(repoIndex.getId(), repository.getFullName(), repository.getCloneUrl(),
                targetBranch, accessToken);
    }

    private void runPipeline(UUID jobId, UUID repositoryId, JobContext ctx) {
        com.changeimpact.common.logging.StageClock clock = com.changeimpact.common.logging.StageClock.start("indexing", "FETCH");
        try {
            runStages(jobId, repositoryId, ctx);
        } finally {
            Map<String, Long> timings = clock.finish();
            log.info("Indexing job {} stage timings (ms): {}", jobId, timings);
            stageTimings.accept("indexing", timings);
        }
    }

    private static final Map<String, String> DISPLAY = Map.of("javascript", "JavaScript/TypeScript", "csharp", "C#", "php", "PHP",
            "sql", "SQL", "css", "CSS", "html", "HTML");
    /** Markup and schema languages: a repository is labelled by them only when it has no program code. */
    private static final java.util.Set<String> NOT_PROGRAMMING = java.util.Set.of("html", "css", "sql");

    static String displayName(String language) {
        return DISPLAY.getOrDefault(language, Character.toUpperCase(language.charAt(0)) + language.substring(1));
    }

    /** Analysed programming language with the most files ("Java", "Python", "C#"), if any file was found. */
    static java.util.Optional<String> dominantLanguage(Map<String, Integer> counts) {
        boolean code = counts.entrySet().stream().anyMatch(e -> e.getValue() > 0 && !NOT_PROGRAMMING.contains(e.getKey()));
        return counts.entrySet().stream().filter(e -> e.getValue() > 0)
                .filter(e -> !code || !NOT_PROGRAMMING.contains(e.getKey()))
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(java.util.Comparator.reverseOrder())))
                .map(e -> displayName(e.getKey()));
    }

    /** "120 Java files" or "120 Java + 14 Python files". */
    static String describe(Map<String, Integer> counts) {
        List<String> parts = new ArrayList<>();
        counts.forEach((language, n) -> { if (n > 0) parts.add(n + " " + displayName(language)); });
        return parts.isEmpty() ? "0 files" : String.join(" + ", parts) + " files";
    }

    private void runStages(UUID jobId, UUID repositoryId, JobContext ctx) {
        try (Git git = workspaceManager.cloneOrFetchRepository(
                repositoryId, ctx.cloneUrl(), ctx.branch(), ctx.accessToken())) {

            String headCommitSha = workspaceManager.getHeadCommitSha(git);
            Path workspacePath = workspaceManager.getWorkspacePath(repositoryId);

            updateProgress(jobId, "SCANNING", "Scanning source files in repository...", 30);
            Map<String, Integer> sourceCounts = codeAnalysisService.sourceFileCounts(workspacePath);
            int sourceFileCount = sourceCounts.values().stream().mapToInt(Integer::intValue).sum();

            transactionTemplate.executeWithoutResult(status -> {
                Repository repository = loadRepository(repositoryId);
                saveBranchMetadata(repository, ctx.branch(), headCommitSha);
            });
            // Commit history intelligence (file churn, revert/bug-fix flags); also feeds the commit pickers.
            updateProgress(jobId, "HISTORY", "Collecting commit history and file churn...", 40);
            historyService.scan(git, repositoryId, headCommitSha, appProperties.getHistory().getMaxCommits()); // never throws

            CodeAnalysisService.AnalysisStats stats = CodeAnalysisService.AnalysisStats.builder().build();
            boolean alreadyAnalysed = symbolRepository.countByRepositoryIdAndCommitSha(repositoryId, headCommitSha) > 0
                    && !symbolRepository.existsByRepositoryIdAndCommitShaAndContentHashIsNull(repositoryId, headCommitSha)
                    && codeAnalysisService.analysedLanguages(repositoryId, headCommitSha).containsAll(codeAnalysisService.enabledLanguages())
                    // an explicit re-index retries a library resolution that failed (network, timeout)
                    && !codeAnalysisService.libraryRetryWanted(repositoryId, headCommitSha);
            if (alreadyAnalysed) {
                // A commit is immutable: re-parsing it would only recreate the same symbols with new IDs and
                // detach every report that references them (FKs are ON DELETE SET NULL). Reuse them instead.
                updateProgress(jobId, "PARSING", "Commit " + headCommitSha.substring(0, 7)
                        + " is already analysed; reusing its symbols", 55);
                stats = CodeAnalysisService.AnalysisStats.builder()
                        .parsedFiles((int) sourceFileRepository.countByRepositoryIdAndCommitSha(repositoryId, headCommitSha))
                        .totalSymbols((int) symbolRepository.countByRepositoryIdAndCommitSha(repositoryId, headCommitSha))
                        .totalDependencies((int) symbolDependencyRepository.countByRepositoryIdAndCommitSha(repositoryId, headCommitSha))
                        .build();
                com.changeimpact.common.metrics.PlatformMetrics.parse("reused", 0, stats.getParsedFiles());
                updateProgress(jobId, "GRAPH_BUILDING", "Loading directed dependency graph (JGraphT)...", 80);
                dependencyGraphService.getGraph(repositoryId, headCommitSha);
                updateProgress(jobId, "ANALYZING", "Re-evaluating architecture rules...", 92);
                transactionTemplate.executeWithoutResult(status -> {
                    architectureViolationRepository.deleteByRepositoryIdAndCommitSha(repositoryId, headCommitSha);
                    architectureRuleEngine.analyzeArchitecture(loadRepository(repositoryId), headCommitSha);
                });
            } else if (sourceFileCount == 0) {
                log.warn("Repository {} contains no source files of the enabled languages {}", ctx.fullName(), sourceCounts.keySet());
            } else {
                updateProgress(jobId, "PARSING",
                        "Extracting AST symbols from " + describe(sourceCounts) + "...", 55);

                var plan = incrementalPlanner.plan(git.getRepository(), repositoryId, headCommitSha).orElse(null);
                stats = transactionTemplate.execute(status -> {
                    Repository repository = loadRepository(repositoryId);
                    clearPreviousAnalysis(repositoryId, headCommitSha);
                    return codeAnalysisService.analyze(repository, headCommitSha, workspacePath, plan);
                });
                // Symbols were (re)created with new IDs: never serve a graph built from the old rows.
                dependencyGraphService.evictCache(repositoryId, headCommitSha);

                updateProgress(jobId, "GRAPH_BUILDING", "Constructing directed dependency graph (JGraphT)...", 80);
                dependencyGraphService.getGraph(repositoryId, headCommitSha);

                updateProgress(jobId, "ANALYZING", "Computing coupling metrics and detecting cyclic loops...", 92);
                transactionTemplate.executeWithoutResult(status ->
                        architectureRuleEngine.analyzeArchitecture(loadRepository(repositoryId), headCommitSha));
            }

            final CodeAnalysisService.AnalysisStats finalStats = stats;
            transactionTemplate.executeWithoutResult(status ->
                    recordSuccess(jobId, repositoryId, ctx, headCommitSha, sourceFileCount, finalStats, sourceCounts));

            com.changeimpact.common.metrics.PlatformMetrics.job("indexing", "completed");
            log.info("Indexing job {} completed for {}: {} files, {} symbols, {} dependencies",
                    jobId, ctx.fullName(), sourceFileCount, stats.getTotalSymbols(), stats.getTotalDependencies());
        }
    }

    /** Commits a progress update immediately so polling clients can observe it. */
    private void updateProgress(UUID jobId, String stage, String message, int percent) {
        com.changeimpact.common.logging.StageClock.mark(stage);
        transactionTemplate.executeWithoutResult(status -> analysisJobRepository.findById(jobId).ifPresent(job -> {
            job.updateProgress(stage, message, percent);
            analysisJobRepository.save(job);
        }));
    }

    /** Makes re-indexing the same commit idempotent. DB cascades remove symbols and dependencies. */
    private void clearPreviousAnalysis(UUID repositoryId, String commitSha) {
        int violations = architectureViolationRepository.deleteByRepositoryIdAndCommitSha(repositoryId, commitSha);
        int files = sourceFileRepository.deleteByRepositoryIdAndCommitSha(repositoryId, commitSha);
        if (files > 0 || violations > 0) {
            log.info("Re-index of commit {}: removed {} previous source files (cascading symbols/dependencies) and {} violations",
                    commitSha, files, violations);
        }
    }

    private void recordSuccess(UUID jobId, UUID repositoryId, JobContext ctx, String headCommitSha,
                               int totalFiles, CodeAnalysisService.AnalysisStats stats, Map<String, Integer> sourceCounts) {
        Instant completedAt = Instant.now();

        RepositoryIndex repoIndex = repositoryIndexRepository.findById(ctx.repositoryIndexId())
                .orElseThrow(() -> new ResourceNotFoundException("RepositoryIndex", ctx.repositoryIndexId()));
        long durationMs = completedAt.toEpochMilli() - repoIndex.getStartedAt().toEpochMilli();
        repoIndex.setCommitSha(headCommitSha);
        repoIndex.setStatus(RepositoryIndex.Status.COMPLETED);
        repoIndex.setCompletedAt(completedAt);
        repoIndex.setDurationMs(durationMs);
        repoIndex.setTotalFiles(totalFiles);
        repoIndex.setIndexedFiles(stats.getParsedFiles());
        repoIndex.setFailedFiles(Math.max(0, totalFiles - stats.getParsedFiles()));
        repoIndex.setTotalSymbols(stats.getTotalSymbols());
        repoIndex.setTotalDependencies(stats.getTotalDependencies());
        repositoryIndexRepository.save(repoIndex);

        Repository repository = loadRepository(repositoryId);
        repository.setIndexingStatus(Repository.IndexingStatus.INDEXED);
        repository.setLastIndexedAt(completedAt);
        repository.setLastIndexedCommit(headCommitSha);
        // GitHub's language when known; otherwise (public repositories connected without the API) the dominant
        // analysed language, so a Python project is not labelled Java
        if (repository.getGithubRepoId() == null || repository.getLanguage() == null) {
            dominantLanguage(sourceCounts).ifPresent(repository::setLanguage);
        }
        repositoryRepository.save(repository);

        Map<String, Object> resultSummary = new LinkedHashMap<>();
        resultSummary.put("status", "SUCCESS");
        resultSummary.put("totalFiles", totalFiles);
        resultSummary.put("parsedFiles", stats.getParsedFiles());
        resultSummary.put("totalSymbols", stats.getTotalSymbols());
        resultSummary.put("totalDependencies", stats.getTotalDependencies());
        resultSummary.put("resolvedInternalDependencies", stats.getTotalDependencies() - stats.getUnresolvedDependencies());
        resultSummary.put("externalOrUnresolvedDependencies", stats.getUnresolvedDependencies());
        resultSummary.put("commitSha", headCommitSha);
        resultSummary.put("branch", ctx.branch());
        resultSummary.put("durationMs", durationMs);

        AnalysisJob job = analysisJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("AnalysisJob", jobId));
        job.markCompleted(resultSummary);
        if (totalFiles == 0) {
            job.updateProgress("COMPLETED", "Completed: 0 Java source files found in this repository.", 100);
        }
        analysisJobRepository.save(job);
    }

    private void recordFailure(UUID jobId, UUID repositoryId, UUID repositoryIndexId, String message) {
        repositoryIndexRepository.findById(repositoryIndexId).ifPresent(repoIndex -> {
            repoIndex.setStatus(RepositoryIndex.Status.FAILED);
            repoIndex.setCompletedAt(Instant.now());
            repoIndex.setErrorMessage(message);
            repositoryIndexRepository.save(repoIndex);
        });
        repositoryRepository.findById(repositoryId).ifPresent(repository -> {
            repository.setIndexingStatus(Repository.IndexingStatus.FAILED);
            repositoryRepository.save(repository);
        });
        analysisJobRepository.findById(jobId).ifPresent(job -> {
            job.markFailed(message);
            analysisJobRepository.save(job);
        });
    }

    private Repository loadRepository(UUID repositoryId) {
        return repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repositoryId));
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        // Keep failure messages short and free of SQL/stack noise for the UI.
        return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
    }

    private void saveBranchMetadata(Repository repository, String branchName, String headCommitSha) {
        RepositoryBranch branch = repositoryBranchRepository.findByRepositoryIdAndName(repository.getId(), branchName)
                .orElseGet(() -> RepositoryBranch.builder()
                        .repository(repository)
                        .name(branchName)
                        .isDefault(branchName.equals(repository.getDefaultBranch()))
                        .build());

        branch.setCommitSha(headCommitSha);
        branch.setLastSeenAt(Instant.now());
        repositoryBranchRepository.save(branch);
    }
}
