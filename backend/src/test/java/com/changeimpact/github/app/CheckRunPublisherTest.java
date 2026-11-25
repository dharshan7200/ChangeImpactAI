package com.changeimpact.github.app;

import com.changeimpact.analysis.entity.ChangeAnalysis;
import com.changeimpact.analysis.entity.ChangedSymbol;
import com.changeimpact.analysis.repository.ChangeAnalysisRepository;
import com.changeimpact.analysis.repository.ChangedSymbolRepository;
import com.changeimpact.analysis.repository.RiskAssessmentRepository;
import com.changeimpact.analysis.service.ChangeAnalysisEvent;
import com.changeimpact.common.config.AppProperties;
import com.changeimpact.reporting.service.ImpactReportService;
import com.changeimpact.securityanalysis.repository.SecurityFindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Check Run publishing: lifecycle, batching, GitHub failures, superseded analyses")
class CheckRunPublisherTest {

    private final AppProperties props = new AppProperties();
    private final GitHubAppAuthService auth = mock(GitHubAppAuthService.class);
    private final GitHubChecksClient checks = mock(GitHubChecksClient.class);
    private final ChangeAnalysisRepository analyses = mock(ChangeAnalysisRepository.class);
    private final ChangedSymbolRepository symbols = mock(ChangedSymbolRepository.class);
    private final RiskAssessmentRepository risks = mock(RiskAssessmentRepository.class);
    private final SecurityFindingRepository security = mock(SecurityFindingRepository.class);
    private final PullRequestRepository prs = mock(PullRequestRepository.class);
    private final ImpactReportService reports = mock(ImpactReportService.class);
    private CheckRunPublisher publisher;
    private ChangeAnalysis analysis;
    private PullRequest pr;

    @BeforeEach
    void setUp() {
        publisher = new CheckRunPublisher(props, auth, checks, analyses, symbols, risks, security, prs, reports,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
        when(auth.isConfigured()).thenReturn(true);
        when(auth.installationToken(anyLong(), anyString())).thenReturn("ghs_token");
        UUID prId = UUID.randomUUID();
        analysis = ChangeAnalysis.builder().id(UUID.randomUUID()).repositoryId(UUID.randomUUID()).ownerId(UUID.randomUUID())
                .jobId(UUID.randomUUID()).baseRef("a").headRef("b").riskLevel("MEDIUM").riskScore(BigDecimal.valueOf(30.1))
                .pullRequestId(prId).summary(Map.of("productionSymbolsChanged", 3))
                .options(new HashMap<>(Map.of("github", Map.of("installationId", 55501, "repository", "demo-org/demo-service",
                        "checkRunId", 9001L)))).build();
        analysis.setStatus(ChangeAnalysis.Status.COMPLETED);
        pr = PullRequest.builder().id(prId).repositoryId(analysis.getRepositoryId()).number(7).state("open")
                .lastAnalysisId(analysis.getId()).checkStatus("QUEUED").build();
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(prs.findById(prId)).thenReturn(Optional.of(pr));
        when(reports.buildReport(any())).thenReturn(Map.of());
        when(reports.toMarkdown(any())).thenReturn("# report");
        when(risks.findByAnalysisId(any())).thenReturn(List.of());
        when(security.findByAnalysisId(any())).thenReturn(List.of());
    }

    private static ChangedSymbol risky(int i) {
        return ChangedSymbol.builder().id(UUID.randomUUID()).qualifiedName("p.X#m" + i + "()").simpleName("m" + i).symbolType("METHOD")
                .changeKinds(new String[]{"BODY_CHANGED"}).headSymbolId(UUID.randomUUID()).filePath("src/main/java/p/X.java")
                .startLine(i + 1).endLine(i + 2).riskScore(BigDecimal.valueOf(40)).riskLevel("MEDIUM").build();
    }

    @Test
    @DisplayName("started → in_progress; completed → conclusion + first 50 annotations, the rest appended in batches of 50")
    @SuppressWarnings("unchecked")
    void lifecycleAndBatches() {
        when(symbols.findByAnalysisIdOrderByRiskScoreDescQualifiedNameAsc(any()))
                .thenReturn(IntStream.range(0, 120).mapToObj(CheckRunPublisherTest::risky).toList());

        publisher.onAnalysisEvent(new ChangeAnalysisEvent(analysis.getId(), ChangeAnalysisEvent.Phase.STARTED));
        assertEquals("IN_PROGRESS", pr.getCheckStatus());
        publisher.onAnalysisEvent(new ChangeAnalysisEvent(analysis.getId(), ChangeAnalysisEvent.Phase.COMPLETED));

        ArgumentCaptor<Map<String, Object>> bodies = ArgumentCaptor.forClass(Map.class);
        verify(checks, times(4)).update(eq("ghs_token"), eq("demo-org/demo-service"), eq(9001L), bodies.capture());
        List<Map<String, Object>> b = bodies.getAllValues();
        assertEquals("in_progress", b.get(0).get("status"));
        assertEquals("completed", b.get(1).get("status"));
        assertEquals("success", b.get(1).get("conclusion"));
        assertEquals(List.of(50, 50, 20), b.subList(1, 4).stream()
                .map(x -> ((List<?>) ((Map<String, Object>) x.get("output")).get("annotations")).size()).toList());
        assertTrue(b.subList(2, 4).stream().allMatch(x -> !x.containsKey("status")), "follow-up updates only append output");
        assertTrue(b.subList(1, 4).stream().allMatch(x -> ((Map<String, Object>) x.get("output")).containsKey("summary")));
        assertEquals("COMPLETED", pr.getCheckStatus());
        assertEquals("success", pr.getCheckConclusion());
        assertEquals("MEDIUM", pr.getRiskLevel());
    }

    @Test
    @DisplayName("a GitHub error is recorded on the pull request without leaking the token")
    void githubFailure() {
        when(symbols.findByAnalysisIdOrderByRiskScoreDescQualifiedNameAsc(any())).thenReturn(List.of());
        doThrow(new IllegalStateException("401 for token ghs_abcdefghijklmnop0123 Bearer ghs_abcdefghijklmnop0123"))
                .when(checks).update(any(), any(), anyLong(), any());
        publisher.onAnalysisEvent(new ChangeAnalysisEvent(analysis.getId(), ChangeAnalysisEvent.Phase.COMPLETED));
        assertEquals("FAILED_TO_PUBLISH", pr.getCheckStatus());
        assertFalse(pr.getCheckError().contains("ghs_abcdefghijklmnop0123"), pr.getCheckError());
        assertEquals("MEDIUM", pr.getRiskLevel(), "the analysis result is still recorded");
    }

    @Test
    @DisplayName("without a check run (creation failed earlier) the webhook-time error is kept and the risk is still recorded")
    void keepsEarlierError() {
        analysis.setOptions(new HashMap<>(Map.of("github", Map.of("installationId", 55501, "repository", "demo-org/demo-service"))));
        pr.setCheckStatus("FAILED_TO_PUBLISH");
        pr.setCheckError("I/O error on POST request");
        publisher.onAnalysisEvent(new ChangeAnalysisEvent(analysis.getId(), ChangeAnalysisEvent.Phase.COMPLETED));
        assertEquals("FAILED_TO_PUBLISH", pr.getCheckStatus());
        assertEquals("I/O error on POST request", pr.getCheckError());
        assertEquals("MEDIUM", pr.getRiskLevel());
        verifyNoInteractions(checks);
    }

    @Test
    @DisplayName("an analysis superseded by a newer push does not overwrite the pull request state")
    void superseded() {
        when(symbols.findByAnalysisIdOrderByRiskScoreDescQualifiedNameAsc(any())).thenReturn(List.of());
        pr.setLastAnalysisId(UUID.randomUUID());
        publisher.onAnalysisEvent(new ChangeAnalysisEvent(analysis.getId(), ChangeAnalysisEvent.Phase.COMPLETED));
        verify(checks).update(any(), any(), eq(9001L), argThat(m -> "completed".equals(m.get("status"))));
        verify(prs, never()).save(any());
        assertNull(pr.getRiskLevel());
    }

    @Test
    @DisplayName("token scrubbing")
    void safeMessage() {
        assertEquals("Bearer [redacted] failed for [redacted]",
                CheckRunPublisher.safeMessage(new RuntimeException("Bearer abc.def-123 failed for ghs_0123456789abcdef")));
    }

    @Test
    @DisplayName("errors name their root cause instead of ': null'")
    void describe() {
        var e = new org.springframework.web.client.ResourceAccessException("I/O error on POST request for \"http://x\": null",
                new java.net.ConnectException());
        assertEquals("I/O error on POST request for \"http://x\": ConnectException", CheckRunPublisher.describe(e));
        assertEquals("IllegalStateException", CheckRunPublisher.describe(new IllegalStateException()));
    }
}
