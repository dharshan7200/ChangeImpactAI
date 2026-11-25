package com.changeimpact.risk.service;

import com.changeimpact.common.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Risk scoring heuristic")
class RiskScoringServiceTest {

    private final AppProperties props = new AppProperties();
    private final RiskScoringService service = new RiskScoringService(props);

    private RiskScoringService.Inputs inputs(double severity, boolean breaking, int direct, int transitive, String exposure,
                                             Integer complexity, int tests, boolean violation, String pkg) {
        return new RiskScoringService.Inputs(severity, breaking, "KIND", direct, transitive, direct > 0 ? 2 : 0, 3,
                exposure, complexity, pkg, "src/main/java/x/X.java", 4L, tests, violation);
    }

    @Test
    @DisplayName("breaking change of an exposed, untested, widely used method scores HIGH or above")
    void highRisk() {
        var r = service.score(inputs(0.9, true, 6, 20, "REST_ENDPOINT", 12, 0, true, "com.shop.web"));
        assertTrue(r.score() >= 50, "score " + r.score());
        assertTrue(r.level().equals("HIGH") || r.level().equals("CRITICAL"));
    }

    @Test
    @DisplayName("private body change without dependents but with tests scores LOW")
    void lowRisk() {
        var r = service.score(inputs(0.45, false, 0, 0, "PRIVATE", 2, 3, false, "com.shop.util"));
        assertEquals("LOW", r.level(), "score " + r.score());
    }

    @Test
    @DisplayName("contributions add up to the score, are sorted, and N/A factors are excluded")
    void explainable() {
        var r = service.score(inputs(0.6, false, 2, 5, "PUBLIC", null, 1, false, "com.shop.service"));
        double sum = r.factors().stream().mapToDouble(RiskScoringService.Factor::contribution).sum();
        assertEquals(r.score(), sum, 0.11);
        for (int i = 1; i < r.factors().size(); i++) {
            assertTrue(r.factors().get(i - 1).contribution() >= r.factors().get(i).contribution());
        }
        assertTrue(r.factors().stream().noneMatch(f -> f.key().equals("complexity")), "complexity is N/A for non-callables");
        r.factors().forEach(f -> assertNotNull(f.explanation()));
    }

    @Test
    @DisplayName("critical module patterns and configurable weights change the result")
    void configurable() {
        var normal = service.score(inputs(0.45, false, 1, 1, "PUBLIC", 3, 1, false, "com.shop.catalog"));
        var security = service.score(inputs(0.45, false, 1, 1, "PUBLIC", 3, 1, false, "com.shop.security"));
        assertTrue(security.score() > normal.score());

        props.getRisk().setWeights(new java.util.LinkedHashMap<>(Map.of("changeSeverity", 1.0)));
        var onlySeverity = service.score(inputs(0.45, false, 9, 9, "PUBLIC", 3, 0, true, "com.shop.security"));
        assertEquals(45.0, onlySeverity.score(), 0.01);
        Map<String, Double> contributions = onlySeverity.factors().stream()
                .collect(Collectors.toMap(RiskScoringService.Factor::key, RiskScoringService.Factor::contribution));
        assertEquals(0.0, contributions.get("directDependents"), 1e-9);
    }

    @Test
    @DisplayName("measured changed-line coverage is scored; the static test-reference proxy then becomes informational")
    void changedLineCoverage() {
        var base = inputs(0.6, false, 2, 5, "REST_ENDPOINT", 4, 0, false, "com.shop.web");
        var covered = new RiskScoringService.Inputs(base.changeSeverity(), base.breaking(), base.changeSummary(), base.directDependents(),
                base.transitiveDependents(), base.maxDepthReached(), base.maxDepth(), base.exposure(), base.complexity(), base.packageName(),
                base.filePath(), base.churnCommits(), base.testReferences(), base.inArchitectureViolation(), 6, 6);
        var uncovered = new RiskScoringService.Inputs(base.changeSeverity(), base.breaking(), base.changeSummary(), base.directDependents(),
                base.transitiveDependents(), base.maxDepthReached(), base.maxDepth(), base.exposure(), base.complexity(), base.packageName(),
                base.filePath(), base.churnCommits(), base.testReferences(), base.inArchitectureViolation(), 0, 6);

        var noCoverage = service.score(base);
        assertTrue(noCoverage.factors().stream().noneMatch(f -> f.key().equals("changedLineCoverage")), "N/A without a JaCoCo report");
        assertTrue(noCoverage.factors().stream().anyMatch(f -> f.key().equals("testReachability") && f.contribution() > 0));

        var full = service.score(covered);
        var none = service.score(uncovered);
        Map<String, RiskScoringService.Factor> f = full.factors().stream().collect(Collectors.toMap(RiskScoringService.Factor::key, x -> x));
        assertEquals(0.0, f.get("changedLineCoverage").normalized(), 1e-9);
        assertEquals("6/6", f.get("changedLineCoverage").raw());
        assertEquals(0.0, f.get("testReachability").weight(), "superseded by the measurement");
        assertTrue(f.get("testReachability").explanation().contains("informational"));
        assertTrue(none.score() > full.score(), "uncovered changed lines raise the risk");
        assertEquals(1.0, none.factors().stream().filter(x -> x.key().equals("changedLineCoverage")).findFirst().orElseThrow().normalized(), 1e-9);
        double sum = full.factors().stream().mapToDouble(RiskScoringService.Factor::contribution).sum();
        assertEquals(full.score(), sum, 0.11);
    }

    @Test
    @DisplayName("no executable changed lines means coverage is not applicable")
    void noExecutableLines() {
        var base = inputs(0.6, false, 2, 5, "PUBLIC", 4, 1, false, "com.shop.web");
        var r = service.score(new RiskScoringService.Inputs(base.changeSeverity(), base.breaking(), base.changeSummary(), base.directDependents(),
                base.transitiveDependents(), base.maxDepthReached(), base.maxDepth(), base.exposure(), base.complexity(), base.packageName(),
                base.filePath(), base.churnCommits(), base.testReferences(), base.inArchitectureViolation(), 0, 0));
        assertTrue(r.factors().stream().noneMatch(x -> x.key().equals("changedLineCoverage")));
    }
}
