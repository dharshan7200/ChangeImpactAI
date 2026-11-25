package com.changeimpact.github.app;

import com.changeimpact.analysis.entity.ChangeAnalysis;
import com.changeimpact.analysis.entity.ChangedSymbol;
import com.changeimpact.analysis.entity.RiskAssessment;
import com.changeimpact.analysis.repository.ChangeAnalysisRepository;
import com.changeimpact.analysis.repository.ChangedSymbolRepository;
import com.changeimpact.analysis.repository.RiskAssessmentRepository;
import com.changeimpact.analysis.service.ChangeAnalysisEvent;
import com.changeimpact.common.config.AppProperties;
import com.changeimpact.reporting.service.ImpactReportService;
import com.changeimpact.securityanalysis.repository.SecurityFindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Publishes pull-request analyses as GitHub Check Runs: {@code queued} when the analysis is requested,
 * {@code in_progress} when it starts, {@code completed} with conclusion, summary and annotations when it ends.
 *
 * <p>GitHub problems never fail an analysis: they are recorded on the pull request ({@code checkStatus =
 * FAILED_TO_PUBLISH}, {@code checkError}). The pull request row is updated with the analysis result even when
 * Check Runs are not configured.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CheckRunPublisher {

    private final AppProperties appProperties;
    private final GitHubAppAuthService authService;
    private final GitHubChecksClient checksClient;
    private final ChangeAnalysisRepository analysisRepository;
    private final ChangedSymbolRepository changedSymbolRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final SecurityFindingRepository securityFindingRepository;
    private final PullRequestRepository pullRequestRepository;
    private final ImpactReportService reportService;
    private final TransactionTemplate transactionTemplate;

    /** Where a PR analysis is published; stored in the analysis options under "github". */
    public record Target(long installationId, String repositoryFullName, int pullNumber, String headSha) {}

    public boolean enabled() {
        return appProperties.getGithub().getChecks().isEnabled() && authService.isConfigured();
    }

    /**
     * Creates the queued check run for a new analysis. Returns the check run id, or empty when Check Runs are
     * not configured or GitHub refused (the reason is returned in {@code error[0]}).
     */
    public Optional<Long> createQueued(Target target, UUID analysisId, String[] error) {
        if (!enabled()) return Optional.empty();
        try {
            String token = authService.installationToken(target.installationId(), target.repositoryFullName());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", appProperties.getGithub().getChecks().getName());
            body.put("head_sha", target.headSha());
            body.put("status", "queued");
            body.put("external_id", analysisId.toString());
            detailsUrl(analysisId).ifPresent(u -> body.put("details_url", u));
            body.put("output", Map.of("title", "Impact analysis queued",
                    "summary", "ChangeImpact AI is analysing the changes of this pull request."));
            return Optional.of(checksClient.create(token, target.repositoryFullName(), body));
        } catch (Exception e) {
            error[0] = safeMessage(e);
            log.warn("Could not create check run for {}#{}: {}", target.repositoryFullName(), target.pullNumber(), error[0]);
            return Optional.empty();
        }
    }

    @EventListener
    public void onAnalysisEvent(ChangeAnalysisEvent event) {
        try {
            handle(event);
        } catch (Exception e) {
            log.warn("Check run update for analysis {} failed: {}", event.analysisId(), safeMessage(e));
        }
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void handle(ChangeAnalysisEvent event) {
        ChangeAnalysis analysis = analysisRepository.findById(event.analysisId()).orElse(null);
        if (analysis == null || analysis.getPullRequestId() == null) return;
        Map<String, Object> gh = analysis.getOptions() != null && analysis.getOptions().get("github") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        Long checkRunId = gh != null && gh.get("checkRunId") instanceof Number n ? n.longValue() : null;
        boolean finished = event.phase() != ChangeAnalysisEvent.Phase.STARTED;

        String checkStatus = null, conclusion = null, error = null;
        if (checkRunId != null && enabled() && gh.get("installationId") instanceof Number inst) {
            String repo = String.valueOf(gh.get("repository"));
            try {
                String token = authService.installationToken(inst.longValue(), repo);
                if (!finished) {
                    checksClient.update(token, repo, checkRunId, Map.of("status", "in_progress", "started_at", Instant.now().toString(),
                            "output", Map.of("title", "Impact analysis running",
                                    "summary", "Parsing both commits, propagating impact and selecting tests…")));
                    checkStatus = "IN_PROGRESS";
                } else {
                    conclusion = complete(analysis, token, repo, checkRunId);
                    checkStatus = "COMPLETED";
                }
            } catch (Exception e) {
                error = safeMessage(e);
                checkStatus = "FAILED_TO_PUBLISH";
                log.warn("Check run {} for analysis {} could not be updated: {}", checkRunId, analysis.getId(), error);
            }
        }
        com.changeimpact.common.metrics.PlatformMetrics.checkRun(checkStatus == null ? "skipped"
                : "FAILED_TO_PUBLISH".equals(checkStatus) ? "failed" : "published");
        String fStatus = checkStatus, fConclusion = conclusion, fError = error;
        transactionTemplate.executeWithoutResult(s -> pullRequestRepository.findById(analysis.getPullRequestId()).ifPresent(pr -> {
            if (!analysis.getId().equals(pr.getLastAnalysisId())) return; // a newer push superseded this analysis
            if (finished) {
                pr.setRiskLevel(analysis.getRiskLevel());
                pr.setRiskScore(analysis.getRiskScore());
            }
            if (fStatus != null) {           // only when a publish was attempted: keep the webhook-time error otherwise
                pr.setCheckStatus(fStatus);
                pr.setCheckError(fError);
            }
            if (fConclusion != null) pr.setCheckConclusion(fConclusion);
            pullRequestRepository.save(pr);
        }));
    }

    private String complete(ChangeAnalysis analysis, String token, String repo, long checkRunId) {
        AppProperties.GitHub.Checks cfg = appProperties.getGithub().getChecks();
        Optional<String> details = detailsUrl(analysis.getId());
        CheckRunOutputBuilder.Output output = transactionTemplate.execute(s -> {
            List<ChangedSymbol> symbols = changedSymbolRepository.findByAnalysisIdOrderByRiskScoreDescQualifiedNameAsc(analysis.getId());
            Map<UUID, RiskAssessment> risk = riskAssessmentRepository.findByAnalysisId(analysis.getId()).stream()
                    .filter(r -> r.getChangedSymbolId() != null)
                    .collect(Collectors.toMap(RiskAssessment::getChangedSymbolId, r -> r, (a, b) -> a));
            String markdown = analysis.getStatus() == ChangeAnalysis.Status.COMPLETED
                    ? reportService.toMarkdown(reportService.buildReport(analysis)) : "";
            return CheckRunOutputBuilder.build(analysis, markdown, symbols, risk,
                    securityFindingRepository.findByAnalysisId(analysis.getId()), cfg.getFailOn(), cfg.getFailOnSecurity(),
                    cfg.getMaxAnnotations(),
                    details.orElse(null));
        });
        List<List<Map<String, Object>>> batches = CheckRunOutputBuilder.batches(output.annotations(),
                CheckRunOutputBuilder.ANNOTATIONS_PER_REQUEST);

        Map<String, Object> first = new LinkedHashMap<>();
        first.put("status", "completed");
        first.put("conclusion", output.conclusion());
        first.put("completed_at", Instant.now().toString());
        details.ifPresent(u -> first.put("details_url", u));
        first.put("output", outputBody(output, batches.isEmpty() ? List.of() : batches.get(0)));
        checksClient.update(token, repo, checkRunId, first);
        // GitHub appends annotations of later updates to the check run
        for (int i = 1; i < batches.size(); i++) {
            checksClient.update(token, repo, checkRunId, Map.of("output", outputBody(output, batches.get(i))));
        }
        log.info("Check run {} for {} completed: {} ({} annotation(s))", checkRunId, repo, output.conclusion(), output.annotations().size());
        return output.conclusion();
    }

    private static Map<String, Object> outputBody(CheckRunOutputBuilder.Output output, List<Map<String, Object>> annotations) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("title", output.title());
        o.put("summary", output.summary());
        if (!annotations.isEmpty()) o.put("annotations", annotations);
        return o;
    }

    private Optional<String> detailsUrl(UUID analysisId) {
        String base = appProperties.getGithub().getPublicUrl();
        if (base == null || base.isBlank()) return Optional.empty();
        return Optional.of(base.replaceAll("/+$", "") + "/analyses/" + analysisId);
    }

    /** Message plus the root cause, e.g. "I/O error on POST …: ConnectException (Connection refused)". */
    static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = e.getMessage() == null ? "" : e.getMessage().replaceAll(":\\s*null$", "");
        String rootText = root.getClass().getSimpleName() + (root.getMessage() != null ? " (" + root.getMessage() + ")" : "");
        if (root == e) return msg.isEmpty() ? rootText : msg;
        return msg.isEmpty() ? rootText : msg + ": " + rootText;
    }

    /** Error text without tokens or response bodies that might echo credentials. */
    static String safeMessage(Exception e) {
        String m = describe(e);
        m = m.replaceAll("(?i)(gh[pousr]_|ghs_|v1\\.)[A-Za-z0-9_.-]{10,}", "[redacted]")
             .replaceAll("(?i)bearer\\s+[A-Za-z0-9._-]+", "Bearer [redacted]");
        return m.length() > 500 ? m.substring(0, 500) + "…" : m;
    }
}
