package com.changeimpact.analysis.service;

import com.changeimpact.analysis.dto.CreateAnalysisRequest;
import com.changeimpact.analysis.entity.*;
import com.changeimpact.analysis.jobs.ChangeAnalysisJobPublisher;
import com.changeimpact.analysis.repository.*;
import com.changeimpact.architecture.repository.ArchitectureViolationRepository;
import com.changeimpact.common.config.AppProperties;
import com.changeimpact.common.exception.BusinessException;
import com.changeimpact.common.exception.IngestionException;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.diff.model.CommitRange;
import com.changeimpact.diff.model.FileCategory;
import com.changeimpact.diff.model.FileChange;
import com.changeimpact.diff.model.Hunk;
import com.changeimpact.diff.semantic.*;
import com.changeimpact.diff.service.GitDiffService;
import com.changeimpact.github.app.GitHubAppAuthService;
import com.changeimpact.github.service.GitHubAccountService;
import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import com.changeimpact.graph.service.DependencyGraphService;
import com.changeimpact.history.service.HistoryService;
import com.changeimpact.impact.service.ImpactPropagationService;
import com.changeimpact.ingestion.workspace.WorkspaceManager;
import com.changeimpact.parser.model.DependencyType;
import com.changeimpact.repository.entity.AnalysisJob;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.entity.User;
import com.changeimpact.repository.repository.AnalysisJobRepository;
import com.changeimpact.repository.repository.RepositoryRepository;
import com.changeimpact.repository.repository.UserRepository;
import com.changeimpact.risk.service.RiskScoringService;
import com.changeimpact.securityanalysis.repository.SecurityFindingRepository;
import com.changeimpact.securityanalysis.service.SecurityAnalysisService;
import com.changeimpact.testing.coverage.CoverageService;
import com.changeimpact.testing.mutation.MutationQualityService;
import com.changeimpact.testing.recommendations.repository.TestRecommendationRepository;
import com.changeimpact.testing.recommendations.service.TestSelectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.jgrapht.Graph;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates a commit-to-commit change impact analysis (Phase 3).
 *
 * <pre>
 *  resolve refs → file diff (JGit, renames) → analyse base &amp; head commits (JavaParser)
 *    → semantic diff (+ configuration key changes) → history/churn
 *    → impact propagation on the dependency graph → explainable risk scoring (+ changed-line coverage)
 *    → persist report → test selection &amp; mutation quality → security impact → COMPLETED
 * </pre>
 *
 * The enrichment stages (tests, security) never fail the analysis: a failure is recorded in the summary.
 *
 * Every step is deterministic. Each stage commits its progress in its own short transaction so the UI
 * can follow it, and failures are recorded on both the analysis and its job.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChangeAnalysisService {

    private static final List<ChangeAnalysis.Status> ACTIVE = List.of(ChangeAnalysis.Status.QUEUED, ChangeAnalysis.Status.RUNNING);

    private final ChangeAnalysisRepository analysisRepository;
    private final ChangedFileRepository changedFileRepository;
    private final ChangedSymbolRepository changedSymbolRepository;
    private final ImpactFindingRepository impactFindingRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final RepositoryRepository repositoryRepository;
    private final UserRepository userRepository;
    private final ArchitectureViolationRepository architectureViolationRepository;
    private final GitHubAccountService gitHubAccountService;
    private final WorkspaceManager workspaceManager;
    private final GitDiffService gitDiffService;
    private final CommitAnalysisService commitAnalysisService;
    private final SemanticChangeDetector semanticChangeDetector;
    private final ConfigChangeAnalyzer configChangeAnalyzer;
    private final HistoryService historyService;
    private final DependencyGraphService dependencyGraphService;
    private final ImpactPropagationService impactPropagationService;
    private final RiskScoringService riskScoringService;
    private final CoverageService coverageService;
    private final TestSelectionService testSelectionService;
    private final MutationQualityService mutationQualityService;
    private final SecurityAnalysisService securityAnalysisService;
    private final com.changeimpact.analysis.review.ReviewerSuggestionService reviewerSuggestionService;
    private final com.changeimpact.architecture.twin.ArchitectureTwinService architectureTwinService;
    private final com.changeimpact.defect.DefectModelService defectModelService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final TestRecommendationRepository testRecommendationRepository;
    private final SecurityFindingRepository securityFindingRepository;
    private final GitHubAppAuthService gitHubAppAuthService;
    private final ApplicationEventPublisher eventPublisher;
    private final java.util.function.BiConsumer<String, Map<String, Long>> stageTimings;
    private final ChangeAnalysisJobPublisher jobPublisher;
    private final AppProperties appProperties;
    private final TransactionTemplate transactionTemplate;

    // ======================================================================
    // Request
    // ======================================================================

    @Transactional
    public ChangeAnalysis create(UUID userId, CreateAnalysisRequest request) {
        Repository repo = repositoryRepository.findByIdAndOwnerIdAndDeletedAtIsNull(request.repositoryId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository", request.repositoryId()));
        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", userId));

        String base = request.baseRef().trim();
        String head = request.headRef().trim();
        if (base.equals(head)) {
            throw new BusinessException("SAME_COMMIT", "Base and head must be different commits");
        }
        if (!analysisRepository.findByRepositoryIdAndBaseRefAndHeadRefAndStatusIn(repo.getId(), base, head, ACTIVE).isEmpty()) {
            throw new BusinessException("ANALYSIS_ALREADY_RUNNING", "An analysis of these commits is already in progress");
        }

        int maxDepth = request.maxDepth() != null ? request.maxDepth() : appProperties.getImpact().getDefaultMaxDepth();
        if (maxDepth < 1 || maxDepth > appProperties.getImpact().getMaxDepthLimit()) {
            throw new BusinessException("INVALID_DEPTH", "maxDepth must be between 1 and " + appProperties.getImpact().getMaxDepthLimit());
        }
        List<String> types = request.relationshipTypes() == null ? List.of() : request.relationshipTypes();
        for (String t : types) {
            try {
                DependencyType.valueOf(t);
            } catch (IllegalArgumentException e) {
                throw new BusinessException("INVALID_RELATIONSHIP", "Unknown relationship type: " + t);
            }
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("maxDepth", maxDepth);
        options.put("relationshipTypes", types);
        options.put("useMergeBase", Boolean.TRUE.equals(request.useMergeBase()));
        return queue(repo, user, base, head, options, "MANUAL", null);
    }

    /**
     * Pull-request analysis requested by a GitHub webhook: merge-base(base, head)..head, i.e. exactly what the
     * pull request introduces. {@code refs/pull/N/head} is fetched so that fork pull requests work.
     * Must run inside a transaction; the job is published after commit. Returns empty when the same range is
     * already being analysed.
     */
    @Transactional
    public Optional<ChangeAnalysis> createForPullRequest(Repository repo, UUID pullRequestId, int number, String baseSha,
                                                        String headSha, Map<String, Object> github) {
        if (!analysisRepository.findByRepositoryIdAndBaseRefAndHeadRefAndStatusIn(repo.getId(), baseSha, headSha, ACTIVE).isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("maxDepth", appProperties.getImpact().getDefaultMaxDepth());
        options.put("relationshipTypes", List.of());
        options.put("useMergeBase", true);
        options.put("pullRequestNumber", number);
        if (github != null) options.put("github", new LinkedHashMap<>(github));
        return Optional.of(queue(repo, repo.getOwner(), baseSha, headSha, options, "PULL_REQUEST", pullRequestId));
    }

    private ChangeAnalysis queue(Repository repo, User user, String base, String head, Map<String, Object> options,
                                 String trigger, UUID pullRequestId) {
        AnalysisJob job = analysisJobRepository.save(AnalysisJob.builder()
                .repository(repo)
                .owner(user)
                .jobType(AnalysisJob.JobType.CHANGE_ANALYSIS)
                .payload(Map.of("baseRef", base, "headRef", head))
                .idempotencyKey("change:" + repo.getId() + ":" + base + ".." + head + ":" + Instant.now().toEpochMilli())
                .build());
        ChangeAnalysis analysis = analysisRepository.save(ChangeAnalysis.builder()
                .repositoryId(repo.getId())
                .ownerId(user.getId())
                .jobId(job.getId())
                .baseRef(base)
                .headRef(head)
                .options(options)
                .trigger(trigger)
                .pullRequestId(pullRequestId)
                .build());

        UUID analysisId = analysis.getId();
        UUID jobId = job.getId();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    jobPublisher.publish(jobId, analysisId);
                }
            });
        } else {
            jobPublisher.publish(jobId, analysisId);
        }
        return analysis;
    }

    // ======================================================================
    // Execution (RabbitMQ worker)
    // ======================================================================

    private record Context(UUID analysisId, UUID jobId, UUID repositoryId, String cloneUrl, String defaultBranch,
                           String token, String baseRef, String headRef, int maxDepth,
                           Set<DependencyType> relationshipTypes, boolean useMergeBase, Integer pullRequestNumber) {}

    public void execute(UUID analysisId) {
        Context ctx = transactionTemplate.execute(status -> start(analysisId));
        if (ctx == null) {
            return;
        }
        eventPublisher.publishEvent(new ChangeAnalysisEvent(analysisId, ChangeAnalysisEvent.Phase.STARTED));
        com.changeimpact.common.logging.StageClock clock = com.changeimpact.common.logging.StageClock.start("analysis", "LOCK_AND_FETCH");
        try {
            workspaceManager.withRepositoryLock(ctx.repositoryId(), appProperties.getIngestion().getJobTimeoutMinutes(), () -> {
                try (Git git = workspaceManager.cloneOrFetchRepository(ctx.repositoryId(), ctx.cloneUrl(), ctx.defaultBranch(), ctx.token())) {
                    run(ctx, git);
                }
                return null;
            });
        } catch (Exception e) {
            String message = rootMessage(e);
            log.error("Change analysis {} failed: {}", analysisId, message, e);
            transactionTemplate.executeWithoutResult(status -> fail(ctx, message));
            eventPublisher.publishEvent(new ChangeAnalysisEvent(analysisId, ChangeAnalysisEvent.Phase.FAILED));
            throw (e instanceof RuntimeException re) ? re : new IngestionException("Change analysis failed: " + message, e);
        } finally {
            Map<String, Long> timings = clock.finish();
            log.info("Change analysis {} stage timings (ms): {}", analysisId, timings);
            stageTimings.accept("analysis", timings);
        }
    }

    private Context start(UUID analysisId) {
        ChangeAnalysis analysis = analysisRepository.findById(analysisId).orElse(null);
        if (analysis == null || analysis.getStatus() != ChangeAnalysis.Status.QUEUED) {
            log.warn("Change analysis {} is missing or not queued; skipping", analysisId);
            return null;
        }
        Repository repo = repositoryRepository.findById(analysis.getRepositoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Repository", analysis.getRepositoryId()));
        analysis.setStatus(ChangeAnalysis.Status.RUNNING);
        analysisRepository.save(analysis);
        analysisJobRepository.findById(analysis.getJobId()).ifPresent(job -> {
            job.markRunning();
            job.updateProgress("FETCHING", "Fetching repository refs...", 5);
            analysisJobRepository.save(job);
        });
        String token = repo.getConnectedAccount() != null
                ? gitHubAccountService.getDecryptedAccessToken(repo.getConnectedAccount()) : null;
        Map<String, Object> o = analysis.getOptions() != null ? analysis.getOptions() : Map.of();
        if (token == null && o.get("github") instanceof Map<?, ?> gh && gh.get("installationId") instanceof Number inst
                && gitHubAppAuthService.isConfigured()) {
            // no connected account: clone/fetch with the GitHub App installation token (read-only contents)
            try {
                token = gitHubAppAuthService.installationToken(inst.longValue(), repo.getFullName());
            } catch (Exception e) {
                log.warn("No installation token for {}: {}", repo.getFullName(), e.getMessage());
            }
        }
        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) o.getOrDefault("relationshipTypes", List.of());
        return new Context(analysisId, analysis.getJobId(), repo.getId(), repo.getCloneUrl(), repo.getDefaultBranch(), token,
                analysis.getBaseRef(), analysis.getHeadRef(),
                ((Number) o.getOrDefault("maxDepth", appProperties.getImpact().getDefaultMaxDepth())).intValue(),
                types.stream().map(DependencyType::valueOf).collect(Collectors.toCollection(() -> EnumSet.noneOf(DependencyType.class))),
                Boolean.TRUE.equals(o.get("useMergeBase")),
                o.get("pullRequestNumber") instanceof Number n ? n.intValue() : null);
    }

    private void run(Context ctx, Git git) throws Exception {
        UUID repoId = ctx.repositoryId();
        gitDiffService.fetchAllRefs(git, WorkspaceManager.credentials(ctx.token()), appProperties.getGithub().getCloneTimeoutSeconds());
        if (ctx.pullRequestNumber() != null) {
            // head commit of a (fork) pull request is only reachable through refs/pull/N/head
            gitDiffService.fetchRefSpec(git, WorkspaceManager.credentials(ctx.token()), appProperties.getGithub().getCloneTimeoutSeconds(),
                    "+refs/pull/" + ctx.pullRequestNumber() + "/head:refs/remotes/origin/pull/" + ctx.pullRequestNumber());
        }

        progress(ctx, "RESOLVING", "Resolving commits " + ctx.baseRef() + " → " + ctx.headRef(), 10);
        CommitRange range;
        try {
            range = gitDiffService.resolveRange(git.getRepository(), ctx.baseRef(), ctx.headRef(), ctx.useMergeBase());
        } catch (BusinessException e) {
            if (ctx.pullRequestNumber() != null && "COMMIT_NOT_FOUND".equals(e.getErrorCode())) {
                throw new BusinessException("COMMIT_NOT_FOUND", e.getMessage() + " (pull request #" + ctx.pullRequestNumber()
                        + ": refs/pull/" + ctx.pullRequestNumber() + "/head could not be fetched, or the commit was force-pushed away)");
            }
            throw e;
        }
        if (range.base().equals(range.head())) {
            throw new BusinessException("SAME_COMMIT", "Both refs resolve to the same commit " + range.head());
        }
        transactionTemplate.executeWithoutResult(s -> analysisRepository.findById(ctx.analysisId()).ifPresent(a -> {
            a.setBaseCommit(range.base());
            a.setHeadCommit(range.head());
            analysisRepository.save(a);
        }));

        progress(ctx, "DIFF", "Comparing commit trees (JGit, rename detection)...", 15);
        List<FileChange> files = gitDiffService.diff(git.getRepository(), range.base(), range.head());

        progress(ctx, "PARSING_BASE", "Parsing base commit " + range.base().substring(0, 7) + "...", 25);
        commitAnalysisService.ensureAnalyzed(git, repoId, range.base());
        progress(ctx, "PARSING_HEAD", "Parsing head commit " + range.head().substring(0, 7) + "...", 40);
        commitAnalysisService.ensureAnalyzed(git, repoId, range.head());

        progress(ctx, "SEMANTIC_DIFF", "Classifying semantic changes...", 55);
        List<SymbolSnapshot> baseSymbols = commitAnalysisService.loadSnapshots(repoId, range.base());
        List<SymbolSnapshot> headSymbols = commitAnalysisService.loadSnapshots(repoId, range.head());
        Map<String, String> renames = new HashMap<>();
        files.stream().filter(f -> "RENAMED".equals(f.changeType())).forEach(f -> renames.put(f.oldPath(), f.path()));
        List<SymbolChange> changes = new ArrayList<>(semanticChangeDetector.detect(baseSymbols, headSymbols, renames));

        List<ConfigChangeAnalyzer.ConfigKeyChange> configChanges = configKeyChanges(git, range, files);
        mergeConfigBindings(changes, headSymbols, configChanges);

        progress(ctx, "HISTORY", "Collecting change history and churn...", 65);
        historyService.scan(git, repoId, range.head(), appProperties.getHistory().getMaxCommits());
        Set<String> paths = changes.stream().map(c -> c.current().filePath()).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, HistoryService.ChurnStats> churn = historyService.churn(repoId, paths);

        progress(ctx, "IMPACT", "Propagating " + changes.size() + " change(s) through the dependency graph...", 75);
        Graph<GraphNode, GraphEdge> baseGraph = dependencyGraphService.getGraph(repoId, range.base());
        Graph<GraphNode, GraphEdge> headGraph = dependencyGraphService.getGraph(repoId, range.head());
        Map<UUID, GraphNode> nodes = new HashMap<>();
        baseGraph.vertexSet().forEach(n -> nodes.put(n.getId(), n));
        headGraph.vertexSet().forEach(n -> nodes.put(n.getId(), n));
        Set<String> violatingTypes = violatingTypes(repoId, range.head());
        Map<UUID, Integer> complexity = new HashMap<>(commitAnalysisService.complexities(repoId, range.base()));
        complexity.putAll(commitAnalysisService.complexities(repoId, range.head()));
        Map<String, SymbolSnapshot> headByName = headSymbols.stream()
                .collect(Collectors.toMap(SymbolSnapshot::qualifiedName, s -> s, (a, b) -> a));
        Map<String, SymbolSnapshot> baseByName = baseSymbols.stream()
                .collect(Collectors.toMap(SymbolSnapshot::qualifiedName, s -> s, (a, b) -> a));
        Map<String, List<Hunk>> hunks = new HashMap<>();
        files.forEach(f -> hunks.put(f.path(), f.hunks()));
        Optional<CoverageService.CoverageIndex> coverage = coverageService.load(repoId, range.head());
        Set<String> changedNames = new HashSet<>();
        changes.forEach(c -> {
            if (c.getBase() != null) changedNames.add(c.getBase().qualifiedName());
            if (c.getHead() != null) changedNames.add(c.getHead().qualifiedName());
        });

        ImpactPropagationService.Options options = new ImpactPropagationService.Options(ctx.maxDepth(), ctx.relationshipTypes());
        List<Evaluated> evaluated = new ArrayList<>();
        for (SymbolChange change : changes) {
            boolean useHead = change.getHead() != null;
            Graph<GraphNode, GraphEdge> graph = useHead ? headGraph : baseGraph;
            GraphNode node = nodes.get(change.current().id());
            List<ImpactPropagationService.Hit> hits = impactPropagationService.propagate(graph, node, options);
            List<Integer> lines = changedLines(change, hunks);
            int[] cov = coverage.isPresent() && !lines.isEmpty() && !change.current().test()
                    ? coverage.get().coverage(change.current().filePath(), lines) : null;
            evaluated.add(evaluate(change, hits, useHead ? range.head() : range.base(), ctx.maxDepth(),
                    headByName, complexity, churn, violatingTypes, nodes, changedNames, lines, cov));
        }

        progress(ctx, "PERSISTING", "Saving impact report...", 90);
        List<UUID> savedIds = transactionTemplate.execute(s -> persist(ctx, range, files, evaluated, configChanges));

        Map<String, Object> enrichment = new LinkedHashMap<>();
        progress(ctx, "TESTS", "Selecting affected tests, checking coverage and mutation results...", 93);
        enrichment.put("tests", testIntelligence(ctx, range, evaluated, savedIds, headGraph, baseGraph,
                headByName, baseByName, complexity, coverage));
        progress(ctx, "SECURITY", "Security impact analysis (sensitive code, Semgrep, dependencies)...", 96);
        enrichment.put("security", securityImpact(ctx, git, range, files, evaluated, headByName, baseByName));
        progress(ctx, "REVIEW", "Suggesting reviewers and comparing the architecture of both commits...", 98);
        enrichment.put("reviewers", reviewers(ctx, git, range, files));
        enrichment.put("architecture", architectureDrift(ctx, range));
        enrichment.put("defectModel", defectModel(ctx, git, range));
        enrichment.put("aiReview", Map.of("status", "ON_REQUEST",
                "detail", "Every finding in this report is deterministic. An AI review that explains it, with validated "
                        + "citations, can be requested separately (POST /analyses/{id}/ai-review) and never changes this report."));
        transactionTemplate.executeWithoutResult(s -> complete(ctx, enrichment, files.size(), evaluated.size()));
        eventPublisher.publishEvent(new ChangeAnalysisEvent(ctx.analysisId(), ChangeAnalysisEvent.Phase.COMPLETED));
        log.info("Change analysis {} completed: {} files, {} symbol changes", ctx.analysisId(), files.size(), changes.size());
    }

    // ----------------------------------------------------------------------
    // Changed lines of a symbol (head commit)
    // ----------------------------------------------------------------------

    /**
     * Head-commit lines of a symbol that this change modified: all lines of an added member, the lines of the
     * diff hunks inside a modified member, nothing for removed symbols. Types are excluded so nested members
     * are not counted twice.
     */
    static List<Integer> changedLines(SymbolChange change, Map<String, List<Hunk>> hunks) {
        SymbolSnapshot s = change.getHead();
        if (s == null || s.isType() || s.startLine() <= 0) return List.of();
        if (change.getBase() == null) {
            List<Integer> all = new ArrayList<>();
            for (int l = s.startLine(); l <= s.endLine(); l++) all.add(l);
            return all;
        }
        TreeSet<Integer> lines = new TreeSet<>();
        for (Hunk h : hunks.getOrDefault(s.filePath(), List.of())) {
            if (!h.overlapsNew(s.startLine(), s.endLine())) continue;
            for (int l = Math.max(h.newStart(), s.startLine()); l <= Math.min(h.newEnd(), s.endLine()); l++) lines.add(l);
        }
        return new ArrayList<>(lines);
    }

    // ----------------------------------------------------------------------
    // Enrichment: test intelligence (Phase 5)
    // ----------------------------------------------------------------------

    private Map<String, Object> testIntelligence(Context ctx, CommitRange range, List<Evaluated> evaluated, List<UUID> savedIds,
                                                 Graph<GraphNode, GraphEdge> headGraph, Graph<GraphNode, GraphEdge> baseGraph,
                                                 Map<String, SymbolSnapshot> headByName, Map<String, SymbolSnapshot> baseByName,
                                                 Map<UUID, Integer> complexity, Optional<CoverageService.CoverageIndex> coverage) {
        try {
            List<TestSelectionService.ChangeInput> inputs = new ArrayList<>();
            List<MutationQualityService.Target> targets = new ArrayList<>();
            for (int i = 0; i < evaluated.size(); i++) {
                Evaluated ev = evaluated.get(i);
                SymbolChange c = ev.change();
                SymbolSnapshot s = c.current();
                inputs.add(new TestSelectionService.ChangeInput(savedIds.get(i), c.qualifiedName(), s.simpleName(),
                        s.symbolType().name(), EnumSet.copyOf(c.getKinds()), c.isBreaking(), ev.risk().level(),
                        c.getHead() == null, s.id(), s.filePath(), s.startLine(), s.endLine(),
                        s.isCallable() ? complexity.get(s.id()) : null, ev.changedLines(), c.getDetails()));
                if (c.getHead() != null && s.isCallable() && !s.test()) {
                    String method = s.symbolType().name().equals("CONSTRUCTOR") ? "<init>" : s.simpleName();
                    targets.add(new MutationQualityService.Target(s.qualifiedName(), ownerTypeName(s.qualifiedName()),
                            method, s.startLine(), s.endLine(), true));
                }
            }
            TestSelectionService.Result result = testSelectionService.select(ctx.analysisId(), ctx.repositoryId(), range.head(),
                    inputs, headGraph, baseGraph, headByName, baseByName, coverage);
            transactionTemplate.executeWithoutResult(s -> testRecommendationRepository.saveAll(result.recommendations()));
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("status", "COMPLETED");
            summary.putAll(result.summary());
            summary.put("mutation", mutationQualityService.evaluate(ctx.repositoryId(), range.head(), targets));
            return summary;
        } catch (Exception e) {
            log.warn("Test intelligence for analysis {} failed: {}", ctx.analysisId(), e.getMessage(), e);
            return Map.of("status", "FAILED", "detail", rootMessage(e));
        }
    }

    // ----------------------------------------------------------------------
    // Enrichment: security impact (Phase 5)
    // ----------------------------------------------------------------------

    /** Reviewer suggestions from code ownership (non-fatal: the report completes without them). */
    private Map<String, Object> reviewers(Context ctx, Git git, CommitRange range, List<FileChange> files) {
        try {
            Set<String> changed = new LinkedHashSet<>();
            files.forEach(f -> {
                changed.add(f.path());
                if (f.oldPath() != null) changed.add(f.oldPath());
            });
            List<String> impacted = jdbcTemplate.queryForList("SELECT DISTINCT file_path FROM impact_findings"
                    + " WHERE analysis_id = ? AND file_path IS NOT NULL AND NOT is_test AND depth <= 2", String.class, ctx.analysisId());
            var r = reviewerSuggestionService.suggest(git, ctx.repositoryId(), range.base(), range.head(), changed,
                    impacted.stream().filter(p -> !changed.contains(p)).toList());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "OK");
            m.put("historyCommits", r.historyCommits());
            m.put("historyUntil", r.historyUntil().toString());
            m.put("changeAuthors", r.changeAuthors());
            m.put("suggestions", r.suggestions());
            m.put("filesWithoutHistory", r.filesWithoutHistory());
            return m;
        } catch (Exception e) {
            log.warn("Reviewer suggestions for analysis {} failed: {}", ctx.analysisId(), e.getMessage());
            return Map.of("status", "FAILED", "detail", String.valueOf(e.getMessage()));
        }
    }

    /** Architecture drift between the two commits (twin comparison; non-fatal). */
    /** Experimental history-based estimate per commit of the range; only from an accepted model (P6-6). */
    private Map<String, Object> defectModel(Context ctx, Git git, CommitRange range) {
        try {
            return defectModelService.forRange(ctx.repositoryId(), git, range.base(), range.head());
        } catch (Exception e) {
            log.warn("Defect model lookup failed for analysis {}: {}", ctx.analysisId(), e.getMessage());
            return Map.of("status", "UNAVAILABLE");
        }
    }

    private Map<String, Object> architectureDrift(Context ctx, CommitRange range) {
        try {
            var base = architectureTwinService.snapshot(ctx.repositoryId(), range.base()).orElseThrow();
            var head = architectureTwinService.snapshot(ctx.repositoryId(), range.head()).orElseThrow();
            var c = architectureTwinService.compare(ctx.repositoryId(), base, head);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "OK");
            m.put("findings", c.findings());
            m.put("newCycles", c.newCycles());
            m.put("resolvedCycles", c.resolvedCycles());
            m.put("addedDependencies", c.addedEdges().stream().limit(20).toList());
            m.put("addedDependencyCount", c.addedEdges().size());
            m.put("removedDependencyCount", c.removedEdges().size());
            m.put("addedPackages", c.addedPackages());
            m.put("removedPackages", c.removedPackages());
            m.put("newViolations", c.newViolations().stream().limit(20).toList());
            m.put("newViolationCount", c.newViolations().size());
            m.put("resolvedViolationCount", c.resolvedViolations().size());
            m.put("before", base.summary());
            m.put("after", head.summary());
            return m;
        } catch (Exception e) {
            log.warn("Architecture drift for analysis {} failed: {}", ctx.analysisId(), e.getMessage());
            return Map.of("status", "FAILED", "detail", String.valueOf(e.getMessage()));
        }
    }

    private Map<String, Object> securityImpact(Context ctx, Git git, CommitRange range, List<FileChange> files,
                                               List<Evaluated> evaluated, Map<String, SymbolSnapshot> headByName,
                                               Map<String, SymbolSnapshot> baseByName) {
        try {
            List<SecurityAnalysisService.ChangedInput> changed = new ArrayList<>();
            Set<String> direct = new LinkedHashSet<>();
            for (Evaluated ev : evaluated) {
                SymbolChange c = ev.change();
                changed.add(new SecurityAnalysisService.ChangedInput(c.current(), c.getHead() == null,
                        c.getKinds().stream().map(Enum::name).toList()));
                if (c.getHead() == null || c.current().test()) continue;
                for (ImpactPropagationService.Hit h : ev.hits()) {
                    if (h.depth() == 1 && !h.node().isTestCode() && !ev.alsoChanged().contains(h.node().getQualifiedName())) {
                        direct.add(h.node().getQualifiedName());
                    }
                }
            }
            SecurityAnalysisService.Result result = securityAnalysisService.analyze(ctx.analysisId(), ctx.repositoryId(),
                    git.getRepository(), range.base(), range.head(), files, changed,
                    direct.stream().map(SecurityAnalysisService.ImpactedInput::new).toList(), headByName, baseByName);
            transactionTemplate.executeWithoutResult(s -> securityFindingRepository.saveAll(result.findings()));
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("status", "COMPLETED");
            summary.putAll(result.summary());
            return summary;
        } catch (Exception e) {
            log.warn("Security analysis for analysis {} failed: {}", ctx.analysisId(), e.getMessage(), e);
            return Map.of("status", "FAILED", "detail", rootMessage(e));
        }
    }

    // ----------------------------------------------------------------------
    // Configuration changes
    // ----------------------------------------------------------------------

    private List<ConfigChangeAnalyzer.ConfigKeyChange> configKeyChanges(Git git, CommitRange range, List<FileChange> files) throws Exception {
        List<ConfigChangeAnalyzer.ConfigKeyChange> result = new ArrayList<>();
        for (FileChange f : files) {
            if (f.category() != FileCategory.CONFIG) {
                continue;
            }
            String before = "ADDED".equals(f.changeType()) ? null
                    : gitDiffService.readFile(git.getRepository(), range.base(), f.oldPath() != null ? f.oldPath() : f.path()).orElse(null);
            String after = "DELETED".equals(f.changeType()) ? null
                    : gitDiffService.readFile(git.getRepository(), range.head(), f.path()).orElse(null);
            result.addAll(configChangeAnalyzer.diffKeys(f.path(), before, after));
        }
        return result;
    }

    private void mergeConfigBindings(List<SymbolChange> changes, List<SymbolSnapshot> headSymbols,
                                     List<ConfigChangeAnalyzer.ConfigKeyChange> configChanges) {
        Map<SymbolSnapshot, List<ConfigChangeAnalyzer.ConfigKeyChange>> bindings =
                configChangeAnalyzer.bindingSymbols(headSymbols, configChanges);
        bindings.forEach((symbol, keys) -> {
            SymbolChange target = changes.stream()
                    .filter(c -> c.getHead() != null && c.getHead().qualifiedName().equals(symbol.qualifiedName()))
                    .findFirst().orElse(null);
            if (target == null) {
                target = new SymbolChange(null, symbol); // code unchanged; only the bound configuration changed
                changes.add(target);
            }
            target.add(ChangeKind.CONFIGURATION_VALUE_CHANGED)
                    .detail("configKeys", keys.stream().map(k -> k.file() + ": " + k.key() + " (" + k.changeType() + ")").toList());
        });
    }

    // ----------------------------------------------------------------------
    // Evaluation (impact + risk) of one changed symbol
    // ----------------------------------------------------------------------

    private record Evaluated(SymbolChange change, List<ImpactPropagationService.Hit> hits, String graphCommit,
                             int direct, int transitive, int tests, RiskScoringService.Result risk,
                             Set<String> alsoChanged, Map<UUID, GraphNode> nodes, List<Integer> changedLines) {}

    private Evaluated evaluate(SymbolChange change, List<ImpactPropagationService.Hit> hits, String graphCommit, int maxDepth,
                               Map<String, SymbolSnapshot> headByName, Map<UUID, Integer> complexity,
                               Map<String, HistoryService.ChurnStats> churn, Set<String> violatingTypes,
                               Map<UUID, GraphNode> nodes, Set<String> changedNames,
                               List<Integer> changedLines, int[] coverage) {
        SymbolSnapshot s = change.current();
        Set<String> prodDirect = new HashSet<>();
        Set<String> prodAll = new HashSet<>();
        Set<String> tests = new HashSet<>();
        int maxReached = 0;
        for (ImpactPropagationService.Hit h : hits) {
            if (h.node().isTestCode()) {
                tests.add(h.node().getQualifiedName());
                continue;
            }
            prodAll.add(h.node().getQualifiedName());
            if (h.depth() == 1) {
                prodDirect.add(h.node().getQualifiedName());
            }
            maxReached = Math.max(maxReached, h.depth());
        }
        String ownerType = ownerTypeName(s.qualifiedName());
        HistoryService.ChurnStats fileChurn = churn.get(s.filePath());

        RiskScoringService.Result risk = riskScoringService.score(new RiskScoringService.Inputs(
                change.severity(),
                change.isBreaking(),
                change.getKinds().stream().map(Enum::name).collect(Collectors.joining(", ")),
                prodDirect.size(),
                prodAll.size(),
                maxReached,
                maxDepth,
                exposure(s, headByName),
                s.isCallable() ? complexity.get(s.id()) : null,
                packageOf(s.qualifiedName()),
                s.filePath(),
                fileChurn != null ? fileChurn.commits() : 0L,
                (change.getHead() == null || s.test()) ? null : tests.size(),
                violatingTypes.contains(ownerType),
                coverage != null && coverage[1] > 0 ? coverage[0] : null,
                coverage != null && coverage[1] > 0 ? coverage[1] : null));
        return new Evaluated(change, hits, graphCommit, prodDirect.size(), prodAll.size(), tests.size(), risk, changedNames, nodes,
                changedLines);
    }

    private static String exposure(SymbolSnapshot s, Map<String, SymbolSnapshot> headByName) {
        String annotations = s.annotationText() == null ? "" : s.annotationText();
        if (annotations.matches("(?s).*@(Request|Get|Post|Put|Delete|Patch)Mapping.*") && !s.isType()) {
            return "REST_ENDPOINT";
        }
        SymbolSnapshot owner = s.parentQualifiedName() != null ? headByName.get(s.parentQualifiedName()) : null;
        if (owner != null && owner.annotationText() != null && owner.annotationText().matches("(?s).*@(RestController|Controller)\\b.*")
                && s.isCallable() && "PUBLIC".equals(s.visibility())) {
            return "REST_ENDPOINT";
        }
        return s.visibility() != null ? s.visibility() : "PACKAGE_PRIVATE";
    }

    private Set<String> violatingTypes(UUID repoId, String commit) {
        return transactionTemplate.execute(status -> {
            Set<String> types = new HashSet<>();
            architectureViolationRepository.findByRepositoryIdAndCommitSha(repoId, commit).forEach(v -> {
                if (v.getSourceSymbol() != null) types.add(ownerTypeName(v.getSourceSymbol().getQualifiedName()));
                if (v.getTargetSymbol() != null) types.add(ownerTypeName(v.getTargetSymbol().getQualifiedName()));
            });
            return types;
        });
    }

    // ----------------------------------------------------------------------
    // Persistence
    // ----------------------------------------------------------------------

    /** Saves files, symbol changes, findings and risk; returns the saved ChangedSymbol ids in {@code evaluated} order. */
    private List<UUID> persist(Context ctx, CommitRange range, List<FileChange> files, List<Evaluated> evaluated,
                               List<ConfigChangeAnalyzer.ConfigKeyChange> configChanges) {
        UUID analysisId = ctx.analysisId();
        List<UUID> savedIds = new ArrayList<>();

        changedFileRepository.saveAll(files.stream().map(f -> ChangedFile.builder()
                .analysisId(analysisId)
                .path(truncate(f.path(), 1024))
                .oldPath(f.oldPath() != null ? truncate(f.oldPath(), 1024) : null)
                .changeType(f.changeType())
                .fileCategory(f.category().name())
                .additions(f.additions())
                .deletions(f.deletions())
                .hunks(f.hunks().stream().map(h -> Map.of("oldStart", h.oldStart(), "oldEnd", h.oldEnd(),
                        "newStart", h.newStart(), "newEnd", h.newEnd())).toList())
                .build()).toList());

        Map<String, Long> riskDistribution = new LinkedHashMap<>();
        for (String level : List.of("LOW", "MEDIUM", "HIGH", "CRITICAL")) riskDistribution.put(level, 0L);
        Map<String, Long> byKind = new TreeMap<>();
        Set<String> impactedDirect = new HashSet<>();
        Set<String> impactedAll = new HashSet<>();
        Set<String> impactedTests = new HashSet<>();
        Map<String, double[]> packages = new HashMap<>();   // [count, maxStrength]
        Map<String, Integer> modules = new TreeMap<>();
        Evaluated top = null;
        int breaking = 0;

        for (Evaluated ev : evaluated) {
            SymbolChange c = ev.change();
            SymbolSnapshot s = c.current();
            boolean testChange = s.test();
            ChangedSymbol saved = changedSymbolRepository.save(ChangedSymbol.builder()
                    .analysisId(analysisId)
                    .qualifiedName(truncate(c.qualifiedName(), 1024))
                    .previousQualifiedName(c.previousQualifiedName() != null ? truncate(c.previousQualifiedName(), 1024) : null)
                    .simpleName(truncate(s.simpleName(), 255))
                    .symbolType(s.symbolType().name())
                    .changeKinds(c.getKinds().stream().map(Enum::name).toArray(String[]::new))
                    .breaking(c.isBreaking())
                    .baseSymbolId(c.getBase() != null ? c.getBase().id() : null)
                    .headSymbolId(c.getHead() != null ? c.getHead().id() : null)
                    .filePath(s.filePath())
                    .startLine(s.startLine())
                    .endLine(s.endLine())
                    .test(testChange)
                    .details(c.getDetails())
                    .directDependents(ev.direct())
                    .transitiveDependents(ev.transitive())
                    .riskScore(BigDecimal.valueOf(ev.risk().score()))
                    .riskLevel(ev.risk().level())
                    .build());
            savedIds.add(saved.getId());

            riskAssessmentRepository.save(RiskAssessment.builder()
                    .analysisId(analysisId)
                    .changedSymbolId(saved.getId())
                    .score(BigDecimal.valueOf(ev.risk().score()))
                    .level(ev.risk().level())
                    .factors(ev.risk().factorMaps())
                    .build());

            List<ImpactFinding> findings = new ArrayList<>();
            for (ImpactPropagationService.Hit h : ev.hits()) {
                GraphNode n = h.node();
                GraphNode owner = n.getOwnerTypeId() != null ? ev.nodes().get(n.getOwnerTypeId()) : null;
                String module = moduleOf(n.getSourceFile());
                findings.add(ImpactFinding.builder()
                        .analysisId(analysisId)
                        .changedSymbolId(saved.getId())
                        .affectedSymbolId(n.getId())
                        .graphCommit(ev.graphCommit())
                        .affectedQualifiedName(truncate(n.getQualifiedName(), 1024))
                        .affectedSimpleName(truncate(n.getSimpleName(), 255))
                        .affectedSymbolType(n.getSymbolType().name())
                        .affectedOwnerType(owner != null ? owner.getQualifiedName() : n.getQualifiedName())
                        .filePath(n.getSourceFile())
                        .startLine(n.getStartLine())
                        .packageName(n.getPackageName())
                        .moduleName(module)
                        .depth(h.depth())
                        .strength(BigDecimal.valueOf(h.strength()).setScale(4, RoundingMode.HALF_UP))
                        .impactType(h.depth() == 1 ? "DIRECT" : "INDIRECT")
                        .evidence(h.depth() == 1 ? "VERIFIED_REFERENCE" : "TRANSITIVE_PROPAGATION")
                        .test(n.isTestCode())
                        .alsoChanged(ev.alsoChanged().contains(n.getQualifiedName()))
                        .dependencyPath(h.path().stream().map(p -> {
                            Map<String, Object> step = new LinkedHashMap<>();
                            step.put("symbolId", p.symbolId());
                            step.put("qualifiedName", p.qualifiedName());
                            step.put("simpleName", p.simpleName());
                            step.put("symbolType", p.symbolType());
                            step.put("relationship", p.relationship());
                            step.put("line", p.line());
                            return step;
                        }).toList())
                        .build());

                if (!testChange) {
                    if (n.isTestCode()) {
                        impactedTests.add(n.getQualifiedName());
                    } else {
                        impactedAll.add(n.getQualifiedName());
                        if (h.depth() == 1) impactedDirect.add(n.getQualifiedName());
                        double[] p = packages.computeIfAbsent(n.getPackageName() == null ? "" : n.getPackageName(), k -> new double[2]);
                        p[0]++;
                        p[1] = Math.max(p[1], h.strength());
                        modules.merge(module, 1, Integer::sum);
                    }
                }
            }
            impactFindingRepository.saveAll(findings);

            c.getKinds().forEach(k -> byKind.merge(k.name(), 1L, Long::sum));
            if (!testChange) {
                riskDistribution.merge(ev.risk().level(), 1L, Long::sum);
                if (c.isBreaking()) breaking++;
                if (top == null || ev.risk().score() > top.risk().score()) top = ev;
            }
        }

        double overall = top != null ? top.risk().score() : 0;
        String level = riskScoringService.level(overall);
        List<Map<String, Object>> overallFactors = new ArrayList<>();
        if (top != null) {
            overallFactors.add(Map.of("key", "highestRiskChange", "label", "Highest-risk production change",
                    "raw", top.change().qualifiedName(), "contribution", top.risk().score(),
                    "explanation", "The change's overall score is the score of its riskiest production symbol change"));
        }
        riskAssessmentRepository.save(RiskAssessment.builder()
                .analysisId(analysisId)
                .score(BigDecimal.valueOf(overall))
                .level(level)
                .factors(overallFactors)
                .build());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("baseCommit", range.base());
        summary.put("headCommit", range.head());
        summary.put("usedMergeBase", range.usedMergeBase());
        summary.put("filesChanged", files.size());
        summary.put("filesByCategory", files.stream().collect(Collectors.groupingBy(f -> f.category().name(), TreeMap::new, Collectors.counting())));
        summary.put("additions", files.stream().mapToInt(FileChange::additions).sum());
        summary.put("deletions", files.stream().mapToInt(FileChange::deletions).sum());
        summary.put("symbolsChanged", evaluated.size());
        summary.put("productionSymbolsChanged", evaluated.stream().filter(e -> !e.change().current().test()).count());
        summary.put("breakingChanges", breaking);
        summary.put("changesByKind", byKind);
        summary.put("directlyImpacted", impactedDirect.size());
        summary.put("totalImpacted", impactedAll.size());
        summary.put("impactedTests", impactedTests.size());
        summary.put("riskDistribution", riskDistribution);
        summary.put("affectedPackages", packages.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                .limit(15)
                .map(e -> Map.of("packageName", e.getKey(), "impactedSymbols", (int) e.getValue()[0],
                        "maxStrength", Math.round(e.getValue()[1] * 1000) / 1000.0))
                .toList());
        summary.put("affectedModules", modules);
        summary.put("configKeyChanges", configChanges.stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", k.file());
            m.put("key", k.key());
            m.put("changeType", k.changeType());
            m.put("before", k.before());
            m.put("after", k.after());
            return m;
        }).toList());
        summary.put("buildFilesChanged", files.stream().anyMatch(f -> f.category() == FileCategory.BUILD));
        Set<String> javaPathsWithSymbolChanges = evaluated.stream().map(e -> e.change().current().filePath()).collect(Collectors.toSet());
        summary.put("cosmeticJavaFiles", files.stream()
                .filter(f -> f.category().isSource()
                        && "MODIFIED".equals(f.changeType()) && !javaPathsWithSymbolChanges.contains(f.path()))
                .map(FileChange::path).toList());

        ChangeAnalysis analysis = analysisRepository.findById(analysisId).orElseThrow();
        analysis.setSummary(summary);
        analysis.setRiskScore(BigDecimal.valueOf(overall));
        analysis.setRiskLevel(level);
        analysisRepository.save(analysis);
        return savedIds;
    }

    /** Adds the enrichment results to the summary and marks the analysis and its job completed. */
    private void complete(Context ctx, Map<String, Object> enrichment, int filesChanged, int symbolsChanged) {
        ChangeAnalysis analysis = analysisRepository.findById(ctx.analysisId()).orElseThrow();
        if (analysis.getStatus() != ChangeAnalysis.Status.RUNNING) {
            // the watchdog timed it out meanwhile; keep that verdict (the Check Run was already completed)
            log.warn("Change analysis {} finished after being marked {}; result discarded", ctx.analysisId(), analysis.getStatus());
            com.changeimpact.common.metrics.PlatformMetrics.job("analysis", "discarded");
            return;
        }
        Map<String, Object> summary = new LinkedHashMap<>(analysis.getSummary() != null ? analysis.getSummary() : Map.of());
        summary.putAll(enrichment);
        analysis.setSummary(summary);
        analysis.setStatus(ChangeAnalysis.Status.COMPLETED);
        analysis.setCompletedAt(Instant.now());
        analysisRepository.save(analysis);

        double score = analysis.getRiskScore() != null ? analysis.getRiskScore().doubleValue() : 0;
        String level = analysis.getRiskLevel() != null ? analysis.getRiskLevel() : "LOW";
        com.changeimpact.common.metrics.PlatformMetrics.job("analysis", "completed");
        com.changeimpact.common.metrics.PlatformMetrics.analysisRisk(level);
        analysisJobRepository.findById(ctx.jobId()).ifPresent(job -> {
            job.markCompleted(Map.of("analysisId", ctx.analysisId().toString(), "riskScore", score, "riskLevel", level,
                    "symbolsChanged", symbolsChanged, "filesChanged", filesChanged));
            job.updateProgress("COMPLETED", "Change impact analysis completed", 100);
            analysisJobRepository.save(job);
        });
    }

    private void fail(Context ctx, String message) {
        analysisRepository.findById(ctx.analysisId()).ifPresent(a -> {
            a.setStatus(ChangeAnalysis.Status.FAILED);
            a.setErrorMessage(message);
            a.setCompletedAt(Instant.now());
            analysisRepository.save(a);
        });
        analysisJobRepository.findById(ctx.jobId()).ifPresent(job -> {
            job.markFailed(message);
            analysisJobRepository.save(job);
        });
        com.changeimpact.common.metrics.PlatformMetrics.job("analysis", "failed");
    }

    private void progress(Context ctx, String stage, String message, int percent) {
        com.changeimpact.common.logging.StageClock.mark(stage);
        transactionTemplate.executeWithoutResult(s -> analysisJobRepository.findById(ctx.jobId()).ifPresent(job -> {
            job.updateProgress(stage, message.length() > 250 ? message.substring(0, 250) : message, percent);
            analysisJobRepository.save(job);
        }));
    }

    // ----------------------------------------------------------------------
    // Name helpers
    // ----------------------------------------------------------------------

    /** {@code a.b.Outer$Inner#m(X)} → {@code a.b.Outer$Inner}. */
    static String ownerTypeName(String qualifiedName) {
        int hash = qualifiedName.indexOf('#');
        return hash > 0 ? qualifiedName.substring(0, hash) : qualifiedName;
    }

    /** Package of a symbol name: strips member part, nested types and the type name. */
    static String packageOf(String qualifiedName) {
        String type = ownerTypeName(qualifiedName);
        int dollar = type.indexOf('$');
        if (dollar > 0) type = type.substring(0, dollar);
        int dot = type.lastIndexOf('.');
        return dot > 0 ? type.substring(0, dot) : "";
    }

    /** Build module of a file: the path prefix before {@code src/}, or "(root)". */
    static String moduleOf(String sourceFile) {
        if (sourceFile == null) return "(root)";
        int idx = sourceFile.indexOf("src/");
        if (idx <= 0) return "(root)";
        String prefix = sourceFile.substring(0, idx);
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    private static String truncate(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = e instanceof BusinessException ? e.getMessage()
                : (root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName());
        return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
    }
}
