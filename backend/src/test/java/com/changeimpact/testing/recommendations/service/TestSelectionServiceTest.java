package com.changeimpact.testing.recommendations.service;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.diff.semantic.ChangeKind;
import com.changeimpact.diff.semantic.SymbolSnapshot;
import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import com.changeimpact.impact.service.ImpactPropagationService;
import com.changeimpact.parser.model.DependencyType;
import com.changeimpact.parser.model.SymbolType;
import com.changeimpact.testing.coverage.CoverageService;
import com.changeimpact.testing.recommendations.entity.TestRecommendation;
import com.changeimpact.testing.reports.entity.QualityReport;
import com.changeimpact.testing.reports.entity.TestExecution;
import com.changeimpact.testing.reports.repository.QualityReportRepository;
import com.changeimpact.testing.reports.repository.TestExecutionRepository;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test selection on a real dependency graph with the real propagation service:
 * <pre>
 *   RepoTest#findsById (@Test) ──calls──▶ Repo#findById ◀──calls── Service#load ◀──calls── ServiceTest#loads (@Test)
 *   RepoTest#setUp (@BeforeEach) ─calls─▶ Repo#findById            Controller#show ──calls──▶ Service#load
 *   RepoTest#other (@Test)  (reaches the change only through setUp)
 * </pre>
 */
@DisplayName("Test selection (reverse graph walk, lifecycle methods, executions, coverage gaps)")
class TestSelectionServiceTest {

    private static final UUID REPO = UUID.randomUUID();
    private static final String HEAD = "a".repeat(40);

    private final QualityReportRepository reports = mock(QualityReportRepository.class);
    private final TestExecutionRepository executions = mock(TestExecutionRepository.class);
    private TestSelectionService service;
    private Graph<GraphNode, GraphEdge> graph;
    private final Map<String, SymbolSnapshot> symbols = new HashMap<>();
    private GraphNode findById, load, show, repoTest, findsById, setUp, other, serviceTest, loads;

    @BeforeEach
    void setUp() {
        service = new TestSelectionService(new ImpactPropagationService(new AppProperties()), reports, executions);
        when(reports.findFirstByRepositoryIdAndCommitShaAndReportTypeOrderByCreatedAtDesc(any(), any(), any())).thenReturn(Optional.empty());
        graph = new DefaultDirectedGraph<>(GraphEdge.class);
        GraphNode repoType = node("p.Repo", SymbolType.CLASS, null, false, null, 1, 30);
        findById = node("p.Repo#findById(String)", SymbolType.METHOD, repoType, false, null, 10, 14);
        load = node("p.Service#load(String)", SymbolType.METHOD, null, false, null, 5, 9);
        show = node("p.Controller#show(String)", SymbolType.METHOD, null, false, "@GetMapping(\"/x\")", 7, 12);
        repoTest = node("p.RepoTest", SymbolType.CLASS, null, true, null, 1, 40);
        findsById = node("p.RepoTest#findsById()", SymbolType.METHOD, repoTest, true, "@Test", 12, 16);
        setUp = node("p.RepoTest#setUp()", SymbolType.METHOD, repoTest, true, "@BeforeEach", 6, 9);
        other = node("p.RepoTest#other()", SymbolType.METHOD, repoTest, true, "@Test\n@DisplayName(\"other\")", 18, 22);
        serviceTest = node("p.ServiceTest", SymbolType.CLASS, null, true, null, 1, 20);
        loads = node("p.ServiceTest#loads()", SymbolType.METHOD, serviceTest, true, "@org.junit.jupiter.api.Test", 8, 12);
        edge(findsById, findById);
        edge(setUp, findById);
        edge(load, findById);
        edge(loads, load);
        edge(show, load);
    }

    private GraphNode node(String qn, SymbolType type, GraphNode owner, boolean test, String annotations, int start, int end) {
        UUID id = UUID.randomUUID();
        String simple = qn.replaceAll("\\(.*$", "").replaceAll(".*[.#]", "");
        GraphNode n = GraphNode.builder().id(id).qualifiedName(qn).simpleName(simple).symbolType(type)
                .ownerTypeId(owner == null ? id : owner.getId()).testCode(test).packageName("p").build();
        graph.addVertex(n);
        String file = (test ? "src/test/java/p/" : "src/main/java/p/") + qn.replaceAll("#.*$", "").replaceAll(".*\\.", "") + ".java";
        symbols.put(qn, new SymbolSnapshot(id, qn, simple, type, owner == null ? null : owner.getQualifiedName(), "PUBLIC", null, "",
                false, false, false, annotations, "h", Set.of(), file, start, end, test));
        return n;
    }

    private void edge(GraphNode from, GraphNode to) {
        graph.addEdge(from, to, new GraphEdge(UUID.randomUUID(), DependencyType.METHOD_CALL, 3, false));
    }

    private TestSelectionService.ChangeInput change(GraphNode n, String risk, List<Integer> lines, ChangeKind... kinds) {
        SymbolSnapshot s = symbols.get(n.getQualifiedName());
        return new TestSelectionService.ChangeInput(UUID.randomUUID(), s.qualifiedName(), s.simpleName(), s.symbolType().name(),
                EnumSet.copyOf(List.of(kinds)), false, risk, false, n.getId(), s.filePath(), s.startLine(), s.endLine(), 3,
                lines, Map.of());
    }

    private Map<String, TestRecommendation> byTest(TestSelectionService.Result r) {
        return r.recommendations().stream().filter(t -> t.getTestMethod() != null)
                .collect(Collectors.toMap(t -> t.getTestClass() + "#" + t.getTestMethod(), t -> t));
    }

    @Test
    @DisplayName("direct, indirect and lifecycle-expanded tests are selected with their path")
    void selection() {
        var result = service.select(UUID.randomUUID(), REPO, HEAD, List.of(change(findById, "HIGH", List.of(11, 12), ChangeKind.BODY_CHANGED)),
                graph, graph, symbols, symbols, Optional.empty());
        Map<String, TestRecommendation> tests = byTest(result);

        assertEquals(Set.of("p.RepoTest#findsById", "p.RepoTest#other", "p.ServiceTest#loads"), tests.keySet(),
                "setUp is a lifecycle method, not a test; it brings in every @Test of its class");
        assertEquals("DIRECT", tests.get("p.RepoTest#findsById").getCategory());
        assertEquals("HIGH", tests.get("p.RepoTest#findsById").getPriority(), "direct test of a HIGH-risk change");
        assertEquals("DIRECT", tests.get("p.RepoTest#other").getCategory());
        assertTrue(tests.get("p.RepoTest#other").getReason().contains("lifecycle method setUp"));
        assertEquals("INDIRECT", tests.get("p.ServiceTest#loads").getCategory(), "reaches the change through production code");
        assertEquals(2, tests.get("p.ServiceTest#loads").getDepth());
        assertEquals("MEDIUM", tests.get("p.ServiceTest#loads").getPriority());
        assertEquals(List.of("p.Repo#findById(String)", "p.Service#load(String)", "p.ServiceTest#loads()"),
                tests.get("p.ServiceTest#loads").getEvidence().get("path"));
        assertTrue(tests.values().stream().allMatch(t -> "NO_RESULTS".equals(t.getExecutionStatus())), "no JUnit report uploaded");
        assertEquals("NO_RESULTS_UPLOADED", result.summary().get("executionResults"));
    }

    @Test
    @DisplayName("executed status comes from the JUnit report of the head commit; parameterized names are normalised")
    void executionStatus() {
        UUID reportId = UUID.randomUUID();
        when(reports.findFirstByRepositoryIdAndCommitShaAndReportTypeOrderByCreatedAtDesc(eq(REPO), eq(HEAD), eq(QualityReport.Type.JUNIT)))
                .thenReturn(Optional.of(QualityReport.builder().id(reportId).repositoryId(REPO).commitSha(HEAD).reportType(QualityReport.Type.JUNIT).build()));
        when(executions.findByReportId(reportId)).thenReturn(List.of(
                TestExecution.builder().className("p.RepoTest").methodName("findsById()").status("PASSED").build(),
                TestExecution.builder().className("p.ServiceTest").methodName("loads()[1]").status("PASSED").build(),
                TestExecution.builder().className("p.ServiceTest").methodName("loads()[2]").status("FAILED").build()));

        var tests = byTest(service.select(UUID.randomUUID(), REPO, HEAD, List.of(change(findById, "LOW", List.of(), ChangeKind.BODY_CHANGED)),
                graph, graph, symbols, symbols, Optional.empty()));
        assertEquals("PASSED", tests.get("p.RepoTest#findsById").getExecutionStatus());
        assertEquals("FAILED", tests.get("p.ServiceTest#loads").getExecutionStatus(), "one failing invocation fails the test");
        assertEquals("NOT_EXECUTED", tests.get("p.RepoTest#other").getExecutionStatus(), "selected but absent from the uploaded run");
    }

    @Test
    @DisplayName("changed code no test reaches becomes a MISSING_TEST gap with JaCoCo evidence; scenarios follow the change kind")
    void gapsWithCoverage() {
        BitSet covered = new BitSet();
        covered.set(8);
        BitSet missed = new BitSet();
        missed.set(9);
        missed.set(10);
        var coverage = new CoverageService.CoverageIndex(UUID.randomUUID(), HEAD,
                Map.of("p/Controller.java", covered), Map.of("p/Controller.java", missed));

        var result = service.select(UUID.randomUUID(), REPO, HEAD,
                List.of(change(show, "HIGH", List.of(8, 9, 10, 11), ChangeKind.PARAMETERS_CHANGED)),
                graph, graph, symbols, symbols, Optional.of(coverage));

        TestRecommendation gap = result.recommendations().stream().filter(r -> "MISSING_TEST".equals(r.getCategory())).findFirst().orElseThrow();
        assertEquals("p.Controller#show(String)", gap.getTargetSymbol());
        assertEquals("HIGH", gap.getPriority(), "high risk and not fully covered");
        assertTrue(gap.getReason().contains("JaCoCo: 1 of 3 changed executable lines covered"), gap.getReason());
        assertTrue(result.recommendations().stream().anyMatch(r -> "SCENARIO".equals(r.getCategory())
                && r.getReason().contains("new parameter list")));
        @SuppressWarnings("unchecked")
        Map<String, Object> cov = (Map<String, Object>) result.summary().get("changedLineCoverage");
        assertEquals(1, cov.get("changedLinesCovered"));
        assertEquals(3, cov.get("changedLinesExecutable"), "line 11 is not executable (in neither set)");
        assertEquals(33.3, cov.get("ratio"));
    }

    @Test
    @DisplayName("a changed test method is itself selected and never reported as missing a test")
    void changedTestMethod() {
        var result = service.select(UUID.randomUUID(), REPO, HEAD, List.of(change(findsById, "LOW", List.of(13), ChangeKind.BODY_CHANGED)),
                graph, graph, symbols, symbols, Optional.empty());
        var tests = byTest(result);
        assertEquals("DIRECT", tests.get("p.RepoTest#findsById").getCategory());
        assertEquals(0, tests.get("p.RepoTest#findsById").getDepth());
        assertTrue(result.recommendations().stream().noneMatch(r -> "MISSING_TEST".equals(r.getCategory()) || "SCENARIO".equals(r.getCategory())));
    }

    @Test
    @DisplayName("priority rules and annotation parsing")
    void helpers() {
        var high = change(findById, "HIGH", List.of(), ChangeKind.BODY_CHANGED);
        var low = change(findById, "LOW", List.of(), ChangeKind.BODY_CHANGED);
        assertEquals("HIGH", TestSelectionService.priority(high, true));
        assertEquals("MEDIUM", TestSelectionService.priority(high, false));
        assertEquals("MEDIUM", TestSelectionService.priority(low, true));
        assertEquals("LOW", TestSelectionService.priority(low, false));
        assertEquals("LOW", TestSelectionService.gapPriority(high, new int[]{3, 3}), "fully covered at runtime");
        assertEquals("HIGH", TestSelectionService.gapPriority(high, new int[]{2, 3}));
        assertEquals(Set.of("Test", "DisplayName"), TestSelectionService.annotationNames(symbols.get("p.RepoTest#other()")));
        assertEquals(Set.of("Test"), TestSelectionService.annotationNames(symbols.get("p.ServiceTest#loads()")), "fully qualified annotation");
    }
}
