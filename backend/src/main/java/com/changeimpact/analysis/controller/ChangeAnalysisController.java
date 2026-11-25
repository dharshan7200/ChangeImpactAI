package com.changeimpact.analysis.controller;

import com.changeimpact.ai.review.AiReviewMarkdown;
import com.changeimpact.ai.review.AiReviewService;
import com.changeimpact.analysis.dto.CreateAnalysisRequest;
import com.changeimpact.analysis.entity.ChangeAnalysis;
import com.changeimpact.analysis.entity.ChangedSymbol;
import com.changeimpact.analysis.entity.ImpactFinding;
import com.changeimpact.analysis.entity.RiskAssessment;
import com.changeimpact.analysis.repository.*;
import com.changeimpact.analysis.service.ChangeAnalysisService;
import com.changeimpact.auth.security.UserPrincipal;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.common.response.ApiResponse;
import com.changeimpact.reporting.service.ImpactReportService;
import com.changeimpact.repository.repository.AnalysisJobRepository;
import com.changeimpact.securityanalysis.entity.SecurityFinding;
import com.changeimpact.securityanalysis.repository.SecurityFindingRepository;
import com.changeimpact.testing.recommendations.entity.TestRecommendation;
import com.changeimpact.testing.recommendations.repository.TestRecommendationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 3: commit comparison, impact analysis and risk reports.
 */
@RestController
@RequestMapping("/api/v1/analyses")
@RequiredArgsConstructor
@SecurityRequirement(name = "BearerAuth")
@Tag(name = "Change Impact Analysis", description = "Commit comparison, semantic changes, impact propagation and risk")
public class ChangeAnalysisController {

    private final ChangeAnalysisService changeAnalysisService;
    private final ChangeAnalysisRepository analysisRepository;
    private final ChangedFileRepository changedFileRepository;
    private final ChangedSymbolRepository changedSymbolRepository;
    private final ImpactFindingRepository impactFindingRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final ImpactReportService reportService;
    private final TestRecommendationRepository testRecommendationRepository;
    private final SecurityFindingRepository securityFindingRepository;
    private final AiReviewService aiReviewService;

    @PostMapping
    @Operation(summary = "Queue a change impact analysis between two commits (SHA, branch or tag)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@AuthenticationPrincipal UserPrincipal principal,
                                                                   @Valid @RequestBody CreateAnalysisRequest request) {
        ChangeAnalysis analysis = changeAnalysisService.create(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success("Change analysis queued",
                Map.of("analysisId", analysis.getId(), "jobId", analysis.getJobId())));
    }

    @GetMapping
    @Operation(summary = "List analyses of a repository (newest first)")
    public ResponseEntity<ApiResponse<Page<Map<String, Object>>>> list(@AuthenticationPrincipal UserPrincipal principal,
                                                                       @RequestParam UUID repositoryId,
                                                                       @RequestParam(defaultValue = "0") int page,
                                                                       @RequestParam(defaultValue = "20") int size) {
        Page<Map<String, Object>> result = analysisRepository
                .findByRepositoryIdAndOwnerIdOrderByCreatedAtDesc(repositoryId, principal.getId(),
                        PageRequest.of(page, Math.min(size, 100)))
                .map(this::header);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Analysis status, progress and summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@AuthenticationPrincipal UserPrincipal principal,
                                                                @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(header(owned(principal, id))));
    }

    @GetMapping("/{id}/changes")
    @Operation(summary = "Changed files and semantically classified changed symbols")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> changes(@AuthenticationPrincipal UserPrincipal principal,
                                                                    @PathVariable UUID id) {
        ChangeAnalysis a = owned(principal, id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("files", changedFileRepository.findByAnalysisIdOrderByPath(a.getId()));
        body.put("symbols", changedSymbolRepository.findByAnalysisIdOrderByRiskScoreDescQualifiedNameAsc(a.getId()));
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @GetMapping("/{id}/impacts")
    @Operation(summary = "Impact findings with dependency paths (filter by changed symbol / DIRECT|INDIRECT)")
    public ResponseEntity<ApiResponse<Page<ImpactFinding>>> impacts(@AuthenticationPrincipal UserPrincipal principal,
                                                                    @PathVariable UUID id,
                                                                    @RequestParam(required = false) UUID changedSymbolId,
                                                                    @RequestParam(required = false) String impactType,
                                                                    @RequestParam(defaultValue = "true") boolean includeTests,
                                                                    @RequestParam(defaultValue = "0") int page,
                                                                    @RequestParam(defaultValue = "50") int size) {
        ChangeAnalysis a = owned(principal, id);
        String type = impactType == null || impactType.isBlank() ? null : impactType.trim().toUpperCase(Locale.ROOT);
        return ResponseEntity.ok(ApiResponse.success(impactFindingRepository.search(a.getId(), changedSymbolId, type,
                includeTests, PageRequest.of(page, Math.min(size, 500)))));
    }

    @GetMapping("/{id}/risk")
    @Operation(summary = "Overall and per-symbol risk with the full factor breakdown")
    public ResponseEntity<ApiResponse<Map<String, Object>>> risk(@AuthenticationPrincipal UserPrincipal principal,
                                                                 @PathVariable UUID id) {
        ChangeAnalysis a = owned(principal, id);
        List<RiskAssessment> all = riskAssessmentRepository.findByAnalysisId(a.getId());
        Map<UUID, ChangedSymbol> symbols = changedSymbolRepository.findByAnalysisIdOrderByRiskScoreDescQualifiedNameAsc(a.getId())
                .stream().collect(Collectors.toMap(ChangedSymbol::getId, s -> s));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("score", a.getRiskScore());
        body.put("level", a.getRiskLevel());
        body.put("disclaimer", ImpactReportService.DISCLAIMER);
        body.put("overall", all.stream().filter(r -> r.getChangedSymbolId() == null).findFirst().orElse(null));
        body.put("symbols", all.stream().filter(r -> r.getChangedSymbolId() != null)
                .sorted(Comparator.comparing(RiskAssessment::getScore).reversed())
                .map(r -> {
                    ChangedSymbol s = symbols.get(r.getChangedSymbolId());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("changedSymbolId", r.getChangedSymbolId());
                    m.put("qualifiedName", s != null ? s.getQualifiedName() : null);
                    m.put("changeKinds", s != null ? s.getChangeKinds() : null);
                    m.put("score", r.getScore());
                    m.put("level", r.getLevel());
                    m.put("factors", r.getFactors());
                    return m;
                }).toList());
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @GetMapping("/{id}/tests")
    @Operation(summary = "Test intelligence: selected tests (direct/indirect), coverage gaps, scenarios, executed status, mutation quality")
    public ResponseEntity<ApiResponse<Map<String, Object>>> tests(@AuthenticationPrincipal UserPrincipal principal,
                                                                  @PathVariable UUID id,
                                                                  @RequestParam(required = false) String category) {
        ChangeAnalysis a = owned(principal, id);
        List<TestRecommendation> all = testRecommendationRepository.findByAnalysisId(a.getId());
        String cat = category == null || category.isBlank() ? null : category.trim().toUpperCase(Locale.ROOT);
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> summary = a.getSummary() != null ? a.getSummary() : Map.of();
        body.put("summary", summary.getOrDefault("tests", Map.of("status", a.getStatus() == ChangeAnalysis.Status.COMPLETED
                ? "NOT_RUN" : "PENDING")));
        body.put("recommendations", all.stream().filter(r -> cat == null || cat.equals(r.getCategory()))
                .sorted(ImpactReportService.TEST_ORDER).toList());
        body.put("note", "Selected by static analysis of the dependency graph; executionStatus comes only from uploaded JUnit results.");
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @GetMapping("/{id}/security")
    @Operation(summary = "Security impact: sensitive changed/affected code, Semgrep findings, dependency changes and known vulnerabilities")
    public ResponseEntity<ApiResponse<Map<String, Object>>> security(@AuthenticationPrincipal UserPrincipal principal,
                                                                     @PathVariable UUID id) {
        ChangeAnalysis a = owned(principal, id);
        List<SecurityFinding> all = new ArrayList<>(securityFindingRepository.findByAnalysisId(a.getId()));
        all.sort(ImpactReportService.SECURITY_ORDER);
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> summary = a.getSummary() != null ? a.getSummary() : Map.of();
        body.put("summary", summary.getOrDefault("security", Map.of("status", a.getStatus() == ChangeAnalysis.Status.COMPLETED
                ? "NOT_RUN" : "PENDING")));
        body.put("findings", all);
        body.put("note", "Security tools are analysis sources, not proof that the application is secure.");
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @GetMapping("/{id}/report")
    @Operation(summary = "Full traceable impact report (JSON)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> report(@AuthenticationPrincipal UserPrincipal principal,
                                                                   @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(reportService.buildReport(owned(principal, id))));
    }

    @GetMapping(value = "/{id}/report.md", produces = "text/markdown")
    @Operation(summary = "Impact report rendered as Markdown (e.g. for pull request comments); ends with the latest completed AI review, if any, labelled as such")
    public ResponseEntity<String> reportMarkdown(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        ChangeAnalysis analysis = owned(principal, id);
        String md = reportService.toMarkdown(reportService.buildReport(analysis)) + AiReviewMarkdown.section(aiReviewService.latest(id));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8")).body(md);
    }

    // ------------------------------------------------------------------

    private ChangeAnalysis owned(UserPrincipal principal, UUID id) {
        return analysisRepository.findByIdAndOwnerId(id, principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("ChangeAnalysis", id));
    }

    private Map<String, Object> header(ChangeAnalysis a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("repositoryId", a.getRepositoryId());
        m.put("baseRef", a.getBaseRef());
        m.put("headRef", a.getHeadRef());
        m.put("baseCommit", a.getBaseCommit());
        m.put("headCommit", a.getHeadCommit());
        m.put("status", a.getStatus());
        m.put("options", a.getOptions());
        m.put("riskScore", a.getRiskScore());
        m.put("riskLevel", a.getRiskLevel());
        m.put("summary", a.getSummary());
        m.put("errorMessage", a.getErrorMessage());
        m.put("createdAt", a.getCreatedAt());
        m.put("completedAt", a.getCompletedAt());
        if (a.getJobId() != null) {
            analysisJobRepository.findById(a.getJobId()).ifPresent(job -> {
                m.put("stage", job.getCurrentStage());
                m.put("progressMessage", job.getCurrentMessage());
                m.put("progressPercent", job.getProgressPercent());
            });
        }
        return m;
    }
}
