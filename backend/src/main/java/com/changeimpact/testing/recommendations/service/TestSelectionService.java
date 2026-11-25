package com.changeimpact.testing.recommendations.service;

import com.changeimpact.diff.semantic.ChangeKind;
import com.changeimpact.diff.semantic.SymbolSnapshot;
import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import com.changeimpact.impact.service.ImpactPropagationService;
import com.changeimpact.parser.model.DependencyType;
import com.changeimpact.testing.coverage.CoverageService;
import com.changeimpact.testing.recommendations.entity.TestRecommendation;
import com.changeimpact.testing.reports.entity.QualityReport;
import com.changeimpact.testing.reports.entity.TestExecution;
import com.changeimpact.testing.reports.repository.QualityReportRepository;
import com.changeimpact.testing.reports.repository.TestExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.jgrapht.Graph;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Selects tests relevant to a change from the dependency graph (static analysis) and reports test gaps.
 *
 * <h2>How tests are found</h2>
 * For every changed production symbol the graph is walked backwards (dependents) up to {@code MAX_DEPTH} hops,
 * over every relationship except IMPORT. Reached symbols in test code are mapped to test methods:
 * <ul>
 *   <li>a method annotated as a test ({@code @Test}, {@code @ParameterizedTest}, ...) is selected itself;</li>
 *   <li>a lifecycle method ({@code @BeforeEach}, {@code @BeforeAll}, ...) selects every test method of its class,
 *       because they all run it — e.g. MockMvc controller tests whose setup stubs the changed repository;</li>
 *   <li>helpers and fields propagate further to the tests that use them.</li>
 * </ul>
 * DIRECT = only test code between the change and the test; INDIRECT = through other production code.
 *
 * <p>Recommendations are never presented as executed: if JUnit results were uploaded for the head commit each
 * recommendation is marked PASSED / FAILED / SKIPPED / NOT_EXECUTED, otherwise NO_RESULTS.
 */
@Service
@RequiredArgsConstructor
public class TestSelectionService {

    static final int MAX_DEPTH = 6;
    static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory",
            "TestTemplate", "Property", "Example");
    static final Set<String> LIFECYCLE_ANNOTATIONS = Set.of("BeforeEach", "BeforeAll", "AfterEach", "AfterAll",
            "Before", "BeforeClass", "After", "AfterClass");
    private static final Set<DependencyType> TEST_RELATIONSHIPS = EnumSet.complementOf(EnumSet.of(DependencyType.IMPORT));

    private final ImpactPropagationService impactPropagationService;
    private final QualityReportRepository reportRepository;
    private final TestExecutionRepository testExecutionRepository;

    /** A changed symbol as seen by test selection (persisted id, risk, kinds, which graph to use). */
    public record ChangeInput(UUID changedSymbolId, String qualifiedName, String simpleName, String symbolType,
                              Set<ChangeKind> kinds, boolean breaking, String riskLevel, boolean removed,
                              UUID graphNodeId, String filePath, int startLine, int endLine, Integer complexity,
                              List<Integer> changedLines, Map<String, Object> details) {}

    public record Result(List<TestRecommendation> recommendations, Map<String, Object> summary) {}

    public Result select(UUID analysisId, UUID repositoryId, String headCommit,
                         List<ChangeInput> changes,
                         Graph<GraphNode, GraphEdge> headGraph, Graph<GraphNode, GraphEdge> baseGraph,
                         Map<String, SymbolSnapshot> headSymbols, Map<String, SymbolSnapshot> baseSymbols,
                         Optional<CoverageService.CoverageIndex> coverage) {

        Map<UUID, GraphNode> headIndex = index(headGraph);
        Map<UUID, GraphNode> baseIndex = index(baseGraph);
        ExecutionLookup executions = executions(repositoryId, headCommit);

        Map<String, Candidate> candidates = new LinkedHashMap<>();
        List<TestRecommendation> gaps = new ArrayList<>();
        List<TestRecommendation> scenarios = new ArrayList<>();
        int changedLinesCovered = 0, changedLinesExecutable = 0;
        List<Map<String, Object>> coverageBySymbol = new ArrayList<>();

        for (ChangeInput c : changes) {
            Graph<GraphNode, GraphEdge> graph = c.removed() ? baseGraph : headGraph;
            Map<UUID, GraphNode> idx = c.removed() ? baseIndex : headIndex;
            Map<String, SymbolSnapshot> symbols = c.removed() ? baseSymbols : headSymbols;
            GraphNode start = c.graphNodeId() == null ? null : idx.get(c.graphNodeId());

            int found = 0;
            boolean testCode = start != null && start.isTestCode();
            if (testCode && !c.removed() && annotationNames(symbols.get(c.qualifiedName())).stream().anyMatch(TEST_ANNOTATIONS::contains)) {
                ImpactPropagationService.Hit self = new ImpactPropagationService.Hit(start, 0, 1.0, List.of(
                        new ImpactPropagationService.PathStep(start.getId(), start.getQualifiedName(), start.getSimpleName(),
                                start.getSymbolType().name(), List.of(), start.getStartLine())));
                found += addCandidate(candidates, start, symbols.get(c.qualifiedName()), c, self, true, "the test method itself was changed");
            }
            if (start != null) {
                List<ImpactPropagationService.Hit> hits = impactPropagationService.propagate(graph, start,
                        new ImpactPropagationService.Options(MAX_DEPTH, TEST_RELATIONSHIPS));
                for (ImpactPropagationService.Hit h : hits) {
                    GraphNode n = h.node();
                    if (!n.isTestCode()) continue;
                    boolean direct = h.path().stream().skip(1).allMatch(step -> {
                        GraphNode s = idx.get(step.symbolId());
                        return s != null && s.isTestCode();
                    });
                    SymbolSnapshot snap = symbols.get(n.getQualifiedName());
                    Set<String> anns = annotationNames(snap);
                    if (anns.stream().anyMatch(TEST_ANNOTATIONS::contains)) {
                        found += addCandidate(candidates, n, snap, c, h, direct, null);
                    } else if (anns.stream().anyMatch(LIFECYCLE_ANNOTATIONS::contains)) {
                        for (GraphNode t : testMethodsOf(n.getOwnerTypeId(), idx, symbols)) {
                            found += addCandidate(candidates, t, symbols.get(t.getQualifiedName()), c, h, direct,
                                    "runs lifecycle method " + n.getSimpleName() + " which reaches the change");
                        }
                    }
                }
            }

            if (testCode) continue; // no coverage, gaps or scenarios for changes of test code itself

            // Changed-line coverage (exact commit only), used for gaps and reporting.
            int[] cov = null;
            if (coverage.isPresent() && !c.removed() && !c.changedLines().isEmpty()) {
                cov = coverage.get().coverage(c.filePath(), c.changedLines());
                changedLinesCovered += cov[0];
                changedLinesExecutable += cov[1];
                if (cov[1] > 0) {
                    coverageBySymbol.add(Map.of("symbol", c.qualifiedName(), "covered", cov[0], "executable", cov[1]));
                }
            }

            boolean callable = "METHOD".equals(c.symbolType()) || "CONSTRUCTOR".equals(c.symbolType()) || "FUNCTION".equals(c.symbolType());
            if (found == 0 && !c.removed() && (callable || c.kinds().contains(ChangeKind.ADDED))) {
                String priority = gapPriority(c, cov);
                StringBuilder reason = new StringBuilder("No test method reaches this changed symbol through the dependency graph");
                if (cov != null && cov[1] > 0) {
                    reason.append(String.format("; JaCoCo: %d of %d changed executable lines covered at runtime", cov[0], cov[1]));
                    if (cov[0] > 0) reason.append(" (exercised indirectly, e.g. via HTTP/MockMvc or reflection)");
                } else if (coverage.isEmpty()) {
                    reason.append("; no JaCoCo report for this commit");
                }
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("changeKinds", c.kinds().stream().map(Enum::name).toList());
                ev.put("riskLevel", c.riskLevel());
                if (cov != null) ev.put("changedLineCoverage", Map.of("covered", cov[0], "executable", cov[1]));
                gaps.add(TestRecommendation.builder().analysisId(analysisId).category("MISSING_TEST").priority(priority)
                        .targetSymbol(c.qualifiedName()).reason(reason.toString()).executionStatus("NOT_APPLICABLE")
                        .evidence(ev).build());
            }

            scenarios.addAll(scenariosFor(analysisId, c));
        }

        List<TestRecommendation> out = new ArrayList<>();
        for (Candidate cand : candidates.values()) {
            String status = executions.status(cand.testClass, cand.testMethod);
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("targets", cand.targets);
            ev.put("path", cand.bestPath);
            if (cand.via != null) ev.put("via", cand.via);
            out.add(TestRecommendation.builder().analysisId(analysisId).category(cand.direct ? "DIRECT" : "INDIRECT")
                    .priority(cand.priority).testSymbolId(cand.testSymbolId).testClass(cand.testClass).testMethod(cand.testMethod)
                    .targetSymbol(cand.targets.get(0)).reason(cand.reason()).executionStatus(status).depth(cand.depth)
                    .evidence(ev).build());
        }
        out.sort(Comparator.comparing((TestRecommendation r) -> rank(r.getPriority()))
                .thenComparing(r -> "DIRECT".equals(r.getCategory()) ? 0 : 1)
                .thenComparing(r -> Objects.toString(r.getTestClass(), "") + "#" + r.getTestMethod()));
        out.addAll(gaps.stream().sorted(Comparator.comparing(r -> rank(r.getPriority()))).toList());
        out.addAll(scenarios);

        for (TestRecommendation r : out) { // column limits
            r.setTestClass(cut(r.getTestClass(), 1024));
            r.setTestMethod(cut(r.getTestMethod(), 512));
            r.setTargetSymbol(cut(r.getTargetSymbol(), 1024));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Long> byCategory = out.stream().collect(Collectors.groupingBy(TestRecommendation::getCategory, TreeMap::new, Collectors.counting()));
        summary.put("byCategory", byCategory);
        summary.put("testClasses", candidates.values().stream().map(cn -> cn.testClass).distinct().count());
        summary.put("executionResults", executions.available ? Map.of("reportId", executions.reportId,
                "byStatus", candidates.values().stream().collect(Collectors.groupingBy(
                        cn -> executions.status(cn.testClass, cn.testMethod), TreeMap::new, Collectors.counting())))
                : "NO_RESULTS_UPLOADED");
        if (coverage.isPresent()) {
            Map<String, Object> cv = new LinkedHashMap<>();
            cv.put("reportId", coverage.get().reportId());
            cv.put("changedLinesCovered", changedLinesCovered);
            cv.put("changedLinesExecutable", changedLinesExecutable);
            cv.put("ratio", changedLinesExecutable == 0 ? null : Math.round(1000.0 * changedLinesCovered / changedLinesExecutable) / 10.0);
            cv.put("bySymbol", coverageBySymbol);
            summary.put("changedLineCoverage", cv);
        } else {
            summary.put("changedLineCoverage", "NO_REPORT_FOR_COMMIT");
        }
        summary.put("note", "Tests are selected by static analysis; recommended is not executed — see executionStatus.");
        return new Result(out, summary);
    }

    // ------------------------------------------------------------------

    private static final class Candidate {
        UUID testSymbolId;
        String testClass;
        String testMethod;
        boolean direct;
        int depth = Integer.MAX_VALUE;
        String priority = "LOW";
        List<String> targets = new ArrayList<>();
        List<String> bestPath = List.of();
        String via;

        String reason() {
            String what = direct ? "Exercises the changed code directly" : "Reaches the changed code through production code";
            String t = targets.size() == 1 ? targets.get(0) : targets.size() + " changed symbols";
            return what + " (" + t + ", " + depth + " hop(s))" + (via != null ? "; " + via : "");
        }
    }

    private int addCandidate(Map<String, Candidate> candidates, GraphNode test, SymbolSnapshot snap, ChangeInput c,
                             ImpactPropagationService.Hit hit, boolean direct, String via) {
        if (test == null) return 0;
        Candidate cand = candidates.computeIfAbsent(test.getQualifiedName(), k -> new Candidate());
        cand.testSymbolId = test.getId();
        String owner = ownerTypeOf(test.getQualifiedName());
        cand.testClass = owner;
        cand.testMethod = test.getSimpleName();
        cand.direct = cand.direct || direct;
        if (hit.depth() < cand.depth) {
            cand.depth = hit.depth();
            cand.bestPath = hit.path().stream().map(ImpactPropagationService.PathStep::qualifiedName).toList();
            cand.via = via;
        }
        if (!cand.targets.contains(c.qualifiedName())) cand.targets.add(c.qualifiedName());
        String p = priority(c, direct);
        if (rank(p) < rank(cand.priority)) cand.priority = p;
        return 1;
    }

    static String priority(ChangeInput c, boolean direct) {
        boolean severe = c.breaking() || "HIGH".equals(c.riskLevel()) || "CRITICAL".equals(c.riskLevel());
        if (direct) return severe ? "HIGH" : "MEDIUM";
        return severe ? "MEDIUM" : "LOW";
    }

    static String gapPriority(ChangeInput c, int[] cov) {
        boolean severe = c.breaking() || "HIGH".equals(c.riskLevel()) || "CRITICAL".equals(c.riskLevel());
        boolean coveredAtRuntime = cov != null && cov[1] > 0 && cov[0] == cov[1];
        if (coveredAtRuntime) return "LOW";
        return severe ? "HIGH" : "MEDIUM";
    }

    private static int rank(String p) {
        return switch (p) { case "HIGH" -> 0; case "MEDIUM" -> 1; default -> 2; };
    }

    /** Rule-based scenarios per change kind; clearly labelled as suggestions. */
    static List<TestRecommendation> scenariosFor(UUID analysisId, ChangeInput c) {
        List<String> s = new ArrayList<>();
        String name = c.simpleName();
        if (c.kinds().contains(ChangeKind.PARAMETERS_CHANGED) || c.kinds().contains(ChangeKind.PARAMETERS_EXTENDED)) s.add("Call " + name + " with the new parameter list, including null/empty/boundary values for added parameters");
        if (c.kinds().contains(ChangeKind.RETURN_TYPE_CHANGED)) s.add("Assert every caller of " + name + " handles the new return type");
        if (c.kinds().contains(ChangeKind.REST_MAPPING_CHANGED)) s.add("HTTP contract test for " + name + ": path, method, status codes and payload of the changed mapping");
        if (c.kinds().contains(ChangeKind.SECURITY_RULE_CHANGED)) s.add("Access tests for " + name + ": authorized, unauthorized and wrong-role requests");
        if (c.kinds().contains(ChangeKind.ENTITY_MAPPING_CHANGED)) s.add("Persistence round-trip test for " + name + " and a schema/migration check");
        if (c.kinds().contains(ChangeKind.TRANSACTION_BOUNDARY_CHANGED)) s.add("Rollback test: an exception inside " + name + " must not leave partial writes");
        if (c.kinds().contains(ChangeKind.CONFIGURATION_VALUE_CHANGED)) s.add("Run " + name + " with the changed configuration values of every profile");
        if (c.kinds().contains(ChangeKind.FIELD_TYPE_CHANGED)) s.add("Serialization/persistence test for the new type of field " + name);
        if (c.kinds().contains(ChangeKind.BODY_CHANGED) && c.complexity() != null && c.complexity() >= 5) {
            s.add("Cover each branch of " + name + " (cyclomatic complexity " + c.complexity() + ")");
        }
        return s.stream().map(text -> TestRecommendation.builder().analysisId(analysisId).category("SCENARIO")
                .priority(c.breaking() ? "MEDIUM" : "LOW").targetSymbol(c.qualifiedName())
                .reason(text + " (rule-based suggestion)").executionStatus("NOT_APPLICABLE")
                .evidence(Map.of("changeKinds", c.kinds().stream().map(Enum::name).toList())).build()).toList();
    }

    private static List<GraphNode> testMethodsOf(UUID ownerTypeId, Map<UUID, GraphNode> idx, Map<String, SymbolSnapshot> symbols) {
        if (ownerTypeId == null) return List.of();
        return idx.values().stream()
                .filter(n -> ownerTypeId.equals(n.getOwnerTypeId()) && !n.isType())
                .filter(n -> annotationNames(symbols.get(n.getQualifiedName())).stream().anyMatch(TEST_ANNOTATIONS::contains))
                .toList();
    }

    static Set<String> annotationNames(SymbolSnapshot s) {
        if (s == null || s.annotationText() == null) return Set.of();
        Set<String> names = new HashSet<>();
        for (String line : s.annotationText().split("\n")) {
            String a = line.trim();
            if (a.startsWith("@")) a = a.substring(1);
            int paren = a.indexOf('(');
            if (paren >= 0) a = a.substring(0, paren);
            names.add(a.substring(a.lastIndexOf('.') + 1).trim());
        }
        return names;
    }

    private static String cut(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }

    static String ownerTypeOf(String qualifiedName) {
        int hash = qualifiedName.indexOf('#');
        return hash > 0 ? qualifiedName.substring(0, hash) : qualifiedName;
    }

    private static Map<UUID, GraphNode> index(Graph<GraphNode, GraphEdge> g) {
        Map<UUID, GraphNode> m = new HashMap<>();
        if (g != null) g.vertexSet().forEach(n -> m.put(n.getId(), n));
        return m;
    }

    // ------------------------------------------------------------------
    // Executed tests (JUnit results uploaded for the head commit)
    // ------------------------------------------------------------------

    private record ExecutionLookup(boolean available, UUID reportId, Map<String, String> statusByTest) {
        String status(String testClass, String testMethod) {
            if (!available) return "NO_RESULTS";
            String s = statusByTest.get(key(testClass, testMethod));
            return s == null ? "NOT_EXECUTED" : s;
        }
    }

    private ExecutionLookup executions(UUID repositoryId, String commit) {
        Optional<QualityReport> report = reportRepository.findFirstByRepositoryIdAndCommitShaAndReportTypeOrderByCreatedAtDesc(
                repositoryId, commit, QualityReport.Type.JUNIT);
        if (report.isEmpty()) return new ExecutionLookup(false, null, Map.of());
        Map<String, String> byTest = new HashMap<>();
        for (TestExecution e : testExecutionRepository.findByReportId(report.get().getId())) {
            String status = "ERROR".equals(e.getStatus()) ? "FAILED" : e.getStatus();
            // A parameterized test runs several invocations: any failure wins over passes.
            byTest.merge(key(e.getClassName(), e.getMethodName()), status,
                    (a, b) -> "FAILED".equals(a) || "FAILED".equals(b) ? "FAILED" : ("PASSED".equals(a) || "PASSED".equals(b) ? "PASSED" : a));
        }
        return new ExecutionLookup(true, report.get().getId(), byTest);
    }

    /** Normalised test key: JUnit nested classes use $, parameterized names carry "(..)" / "[..]" suffixes. */
    static String key(String testClass, String testMethod) {
        String m = testMethod == null ? "" : testMethod;
        int cut = m.length();
        int paren = m.indexOf('('), bracket = m.indexOf('[');
        if (paren >= 0) cut = Math.min(cut, paren);
        if (bracket >= 0) cut = Math.min(cut, bracket);
        return (testClass == null ? "" : testClass.replace('$', '.')) + "#" + m.substring(0, cut).trim();
    }
}
