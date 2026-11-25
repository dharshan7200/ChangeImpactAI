package com.changeimpact.dashboard;

import com.changeimpact.analysis.entity.ChangeAnalysis;
import com.changeimpact.analysis.repository.ChangeAnalysisRepository;
import com.changeimpact.architecture.repository.ArchitectureViolationRepository;
import com.changeimpact.github.app.PullRequest;
import com.changeimpact.github.app.PullRequestRepository;
import com.changeimpact.repository.entity.AnalysisJob;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.repository.AnalysisJobRepository;
import com.changeimpact.repository.repository.RepositoryRepository;
import com.changeimpact.securityanalysis.entity.SecurityFinding;
import com.changeimpact.securityanalysis.repository.SecurityFindingRepository;
import com.changeimpact.testing.reports.entity.QualityReport;
import com.changeimpact.testing.reports.repository.QualityReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Team dashboard: open pull request risk, high-risk changes, repository health (index state, coverage, tests,
 * mutation score, known vulnerabilities, architecture violations), security findings, risk trend and execution
 * history for the user's repositories. Every number is an aggregate of stored analysis results; when a pull request or
 * commit range was analysed more than once, only its latest completed analysis counts.
 */
@Service
@RequiredArgsConstructor
public class TeamDashboardService {

    private static final List<String> LEVELS = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final RepositoryRepository repositoryRepository;
    private final ChangeAnalysisRepository analysisRepository;
    private final PullRequestRepository pullRequestRepository;
    private final SecurityFindingRepository securityFindingRepository;
    private final QualityReportRepository reportRepository;
    private final ArchitectureViolationRepository violationRepository;
    private final AnalysisJobRepository jobRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> build(UUID userId, int days) {
        Instant since = Instant.now().minus(Duration.ofDays(days));
        List<Repository> repos = repositoryRepository.findByOwnerIdAndDeletedAtIsNullOrderByFullNameAsc(userId);
        Map<UUID, String> repoNames = repos.stream().collect(Collectors.toMap(Repository::getId, Repository::getFullName));
        List<ChangeAnalysis> analyses = analysisRepository.findByOwnerIdAndCreatedAtAfterOrderByCreatedAtDesc(userId, since).stream()
                .filter(a -> repoNames.containsKey(a.getRepositoryId())).toList();
        // Re-analysing the same pull request or commit range must not multiply findings: keep the latest per change.
        Map<String, ChangeAnalysis> latestPerChange = new LinkedHashMap<>();
        analyses.stream().filter(a -> a.getStatus() == ChangeAnalysis.Status.COMPLETED)
                .forEach(a -> latestPerChange.putIfAbsent(changeKey(a), a)); // analyses are newest first
        List<ChangeAnalysis> completed = new ArrayList<>(latestPerChange.values());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowDays", days);
        out.put("generatedAt", Instant.now());

        // ---- headline numbers
        List<PullRequest> openPrs = repos.isEmpty() ? List.of()
                : pullRequestRepository.findByRepositoryIdInAndStateOrderByUpdatedAtDesc(repoNames.keySet(), "open");
        List<SecurityFinding> findings = completed.isEmpty() ? List.of()
                : securityFindingRepository.findByAnalysisIdIn(completed.stream().map(ChangeAnalysis::getId).toList());
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("repositories", repos.size());
        totals.put("indexedRepositories", repos.stream().filter(r -> "INDEXED".equals(String.valueOf(r.getIndexingStatus()))).count());
        totals.put("analyses", analyses.size());
        totals.put("distinctChanges", completed.size());
        totals.put("failedAnalyses", analyses.stream().filter(a -> a.getStatus() == ChangeAnalysis.Status.FAILED).count());
        totals.put("highRiskChanges", completed.stream().filter(a -> rank(a.getRiskLevel()) >= 2).count());
        totals.put("openPullRequests", openPrs.size());
        totals.put("openHighRiskPullRequests", openPrs.stream().filter(p -> rank(p.getRiskLevel()) >= 2).count());
        totals.put("securityFindingsHighOrCritical", findings.stream().filter(f -> rank(f.getSeverity()) >= 2).count());
        out.put("totals", totals);

        // ---- open pull requests, riskiest first
        out.put("openPullRequests", openPrs.stream()
                .sorted(Comparator.comparingInt((PullRequest p) -> -rank(p.getRiskLevel()))
                        .thenComparing(PullRequest::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(20).map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("repository", repoNames.get(p.getRepositoryId()));
                    m.put("repositoryId", p.getRepositoryId());
                    m.put("number", p.getNumber());
                    m.put("title", p.getTitle());
                    m.put("author", p.getAuthor());
                    m.put("url", p.getUrl());
                    m.put("draft", p.isDraft());
                    m.put("riskLevel", p.getRiskLevel());
                    m.put("riskScore", p.getRiskScore());
                    m.put("checkStatus", p.getCheckStatus());
                    m.put("checkConclusion", p.getCheckConclusion());
                    m.put("analysisId", p.getLastAnalysisId());
                    m.put("updatedAt", p.getUpdatedAt());
                    return m;
                }).toList());

        // ---- high-risk changes
        out.put("highRiskChanges", completed.stream().filter(a -> rank(a.getRiskLevel()) >= 2)
                .sorted(Comparator.comparing(ChangeAnalysis::getRiskScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(15).map(a -> analysisView(a, repoNames)).toList());

        // ---- security: introduced vulnerabilities and sensitive changes in the window
        Map<UUID, ChangeAnalysis> byId = completed.stream().collect(Collectors.toMap(ChangeAnalysis::getId, a -> a));
        Map<String, Object> security = new LinkedHashMap<>();
        security.put("bySeverity", count(findings, SecurityFinding::getSeverity));
        security.put("byCategory", count(findings, SecurityFinding::getCategory));
        security.put("bySource", count(findings, SecurityFinding::getSource));
        security.put("top", findings.stream().filter(f -> rank(f.getSeverity()) >= 2)
                .sorted(Comparator.comparingInt((SecurityFinding f) -> -rank(f.getSeverity())))
                .limit(10).map(f -> {
                    ChangeAnalysis a = byId.get(f.getAnalysisId());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("severity", f.getSeverity());
                    m.put("title", f.getTitle());
                    m.put("source", f.getSource());
                    m.put("location", f.getFilePath() == null ? null : f.getFilePath() + (f.getStartLine() != null ? ":" + f.getStartLine() : ""));
                    m.put("repository", a != null ? repoNames.get(a.getRepositoryId()) : null);
                    m.put("analysisId", f.getAnalysisId());
                    return m;
                }).toList());
        out.put("security", security);

        // ---- risk trend: one point per day in the window (days without analyses are kept as gaps)
        ZoneId utc = ZoneOffset.UTC;
        Map<LocalDate, List<ChangeAnalysis>> perDay = completed.stream()
                .filter(a -> a.getCreatedAt() != null && a.getRiskScore() != null)
                .collect(Collectors.groupingBy(a -> LocalDate.ofInstant(a.getCreatedAt(), utc)));
        List<Map<String, Object>> trend = new ArrayList<>();
        for (LocalDate d = LocalDate.ofInstant(since, utc).plusDays(1); !d.isAfter(LocalDate.now(utc)); d = d.plusDays(1)) {
            List<ChangeAnalysis> day = perDay.getOrDefault(d, List.of());
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", d.toString());
            point.put("analyses", day.size());
            point.put("maxScore", day.stream().map(ChangeAnalysis::getRiskScore).max(Comparator.naturalOrder()).orElse(null));
            point.put("avgScore", day.isEmpty() ? null : BigDecimal.valueOf(day.stream().mapToDouble(a -> a.getRiskScore().doubleValue())
                    .average().orElse(0)).setScale(1, java.math.RoundingMode.HALF_UP));
            point.put("high", day.stream().filter(a -> rank(a.getRiskLevel()) >= 2).count());
            trend.add(point);
        }
        out.put("riskTrend", trend);

        // ---- repository health
        out.put("repositories", repos.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("repositoryId", r.getId());
            m.put("fullName", r.getFullName());
            m.put("indexingStatus", r.getIndexingStatus());
            m.put("lastIndexedAt", r.getLastIndexedAt());
            m.put("lastIndexedCommit", r.getLastIndexedCommit());
            m.put("architectureViolations", r.getLastIndexedCommit() == null ? null
                    : violationRepository.countByRepositoryIdAndCommitSha(r.getId(), r.getLastIndexedCommit()));
            m.put("coverage", latest(r.getId(), QualityReport.Type.JACOCO, "lineCoverage"));
            m.put("tests", latest(r.getId(), QualityReport.Type.JUNIT, "byStatus"));
            m.put("mutationScore", latest(r.getId(), QualityReport.Type.PIT, "mutationScore"));
            m.put("knownVulnerabilities", latest(r.getId(), QualityReport.Type.DEPENDENCY_CHECK, "bySeverity"));
            List<ChangeAnalysis> mine = completed.stream().filter(a -> a.getRepositoryId().equals(r.getId())).toList();
            m.put("analyses", mine.size());
            m.put("lastAnalysis", mine.isEmpty() ? null : analysisView(mine.get(0), repoNames));
            return m;
        }).toList());

        // ---- execution history (indexing + analysis jobs)
        out.put("executionHistory", jobRepository.findByOwnerIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 15)).stream().map(j -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", j.getId());
            m.put("type", j.getJobType());
            m.put("status", j.getStatus());
            m.put("repository", j.getRepository() != null ? j.getRepository().getFullName() : null);
            m.put("stage", j.getCurrentStage());
            m.put("createdAt", j.getCreatedAt());
            m.put("durationMs", j.getDurationMs() != null ? j.getDurationMs()
                    : j.getStartedAt() != null && j.getCompletedAt() != null
                    ? Duration.between(j.getStartedAt(), j.getCompletedAt()).toMillis() : null);
            m.put("error", j.getErrorMessage());
            return m;
        }).toList());
        out.put("note", "Risk scores are a transparent heuristic (0-100), not probabilities. Coverage, tests, mutation and "
                + "vulnerability figures come from the latest uploaded report of each repository.");
        return out;
    }

    private Map<String, Object> latest(UUID repoId, QualityReport.Type type, String key) {
        return reportRepository.findFirstByRepositoryIdAndReportTypeOrderByCreatedAtDesc(repoId, type).map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", r.getSummary() != null ? r.getSummary().get(key) : null);
            m.put("commitSha", r.getCommitSha());
            m.put("reportedAt", r.getCreatedAt());
            return m;
        }).orElse(null);
    }

    private static Map<String, Object> analysisView(ChangeAnalysis a, Map<UUID, String> repoNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("analysisId", a.getId());
        m.put("repository", repoNames.get(a.getRepositoryId()));
        m.put("baseRef", a.getBaseRef());
        m.put("headRef", a.getHeadRef());
        m.put("riskLevel", a.getRiskLevel());
        m.put("riskScore", a.getRiskScore());
        m.put("trigger", a.getTrigger());
        m.put("pullRequestNumber", a.getOptions() != null ? a.getOptions().get("pullRequestNumber") : null);
        m.put("createdAt", a.getCreatedAt());
        return m;
    }

    private static <T> Map<String, Long> count(List<T> items, java.util.function.Function<T, String> key) {
        return items.stream().collect(Collectors.groupingBy(i -> Objects.toString(key.apply(i), "UNKNOWN"), TreeMap::new, Collectors.counting()));
    }

    /** A pull request, or else a repository + base..head range. */
    static String changeKey(ChangeAnalysis a) {
        return a.getPullRequestId() != null ? "pr:" + a.getPullRequestId()
                : a.getRepositoryId() + "|" + a.getBaseRef() + ".." + a.getHeadRef();
    }

    private static int rank(String level) {
        return level == null ? -1 : LEVELS.indexOf(level);
    }
}
