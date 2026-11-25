package com.changeimpact.github.app;

import com.changeimpact.analysis.entity.ChangeAnalysis;
import com.changeimpact.analysis.entity.ChangedSymbol;
import com.changeimpact.analysis.entity.RiskAssessment;
import com.changeimpact.securityanalysis.entity.SecurityFinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Check Run output: conclusion policy, title, summary limit, annotations")
class CheckRunOutputBuilderTest {

    private static ChangeAnalysis analysis(String level, double score) {
        ChangeAnalysis a = ChangeAnalysis.builder().repositoryId(UUID.randomUUID()).ownerId(UUID.randomUUID()).jobId(UUID.randomUUID())
                .baseRef("a").headRef("b").riskLevel(level).riskScore(BigDecimal.valueOf(score)).build();
        a.setStatus(ChangeAnalysis.Status.COMPLETED);
        a.setSummary(Map.of("productionSymbolsChanged", 3, "totalImpacted", 2,
                "tests", Map.of("byCategory", Map.of("DIRECT", 3, "INDIRECT", 1)),
                "security", Map.of("bySeverity", Map.of("HIGH", 1, "LOW", 2))));
        return a;
    }

    private static SecurityFinding finding(String source, String relation, String severity, String path, Integer line) {
        return SecurityFinding.builder().analysisId(UUID.randomUUID()).source(source).category("SQL").severity(severity)
                .title(source + " " + severity).message("m").filePath(path).startLine(line).endLine(line).relation(relation).build();
    }

    private static ChangedSymbol symbol(String qn, String level, double score, boolean test, boolean removed) {
        return ChangedSymbol.builder().id(UUID.randomUUID()).analysisId(UUID.randomUUID()).qualifiedName(qn).simpleName(qn)
                .symbolType("METHOD").changeKinds(new String[]{"BODY_CHANGED"}).breaking(false)
                .headSymbolId(removed ? null : UUID.randomUUID()).filePath("src/main/java/p/X.java").startLine(10).endLine(20)
                .test(test).directDependents(2).transitiveDependents(5).riskScore(BigDecimal.valueOf(score)).riskLevel(level).build();
    }

    @Test
    @DisplayName("conclusion: risk threshold, introduced-vulnerability threshold, neutral band, NEVER")
    void conclusions() {
        assertEquals("success", CheckRunOutputBuilder.conclusion("MEDIUM", "CRITICAL", null, "HIGH"));
        assertEquals("neutral", CheckRunOutputBuilder.conclusion("HIGH", "CRITICAL", null, "HIGH"), "HIGH below failOn");
        assertEquals("failure", CheckRunOutputBuilder.conclusion("CRITICAL", "CRITICAL", null, "HIGH"));
        assertEquals("failure", CheckRunOutputBuilder.conclusion("LOW", "CRITICAL", "HIGH", "HIGH"), "introduced HIGH vulnerability");
        assertEquals("neutral", CheckRunOutputBuilder.conclusion("LOW", "CRITICAL", "MEDIUM", "HIGH"));
        assertEquals("success", CheckRunOutputBuilder.conclusion("LOW", "CRITICAL", "LOW", "HIGH"));
        assertEquals("neutral", CheckRunOutputBuilder.conclusion("CRITICAL", "NEVER", "CRITICAL", "never"), "NEVER disables failing");
        assertEquals("failure", CheckRunOutputBuilder.conclusion("MEDIUM", "medium", null, "HIGH"), "case-insensitive threshold");
    }

    @Test
    @DisplayName("only Semgrep matches in changed lines and Dependency-Check CVEs count as introduced vulnerabilities")
    void introduced() {
        assertTrue(CheckRunOutputBuilder.introducedVulnerability(finding("SEMGREP", "CHANGED", "HIGH", "a", 1)));
        assertTrue(CheckRunOutputBuilder.introducedVulnerability(finding("DEPENDENCY_CHECK", "DEPENDENCY", "CRITICAL", "pom.xml", 3)));
        assertFalse(CheckRunOutputBuilder.introducedVulnerability(finding("SEMGREP", "IMPACTED", "HIGH", "a", 1)), "pre-existing code");
        assertFalse(CheckRunOutputBuilder.introducedVulnerability(finding("STATIC_RULES", "CHANGED", "HIGH", "a", 1)), "review hint only");

        var out = CheckRunOutputBuilder.build(analysis("LOW", 12), "# r", List.of(), Map.of(),
                List.of(finding("STATIC_RULES", "CHANGED", "HIGH", "a", 1)), "CRITICAL", "HIGH", 150, null);
        assertEquals("success", out.conclusion(), "sensitive-area hints never fail a check");
        out = CheckRunOutputBuilder.build(analysis("LOW", 12), "# r", List.of(), Map.of(),
                List.of(finding("SEMGREP", "CHANGED", "HIGH", "a", 1)), "CRITICAL", "HIGH", 150, null);
        assertEquals("failure", out.conclusion());
    }

    @Test
    @DisplayName("title summarises risk, scope, selected tests and severe findings")
    void title() {
        var out = CheckRunOutputBuilder.build(analysis("HIGH", 53.0), "# r", List.of(), Map.of(), List.of(), "CRITICAL", "HIGH", 150, null);
        assertEquals("Risk HIGH (53/100) · 3 symbol(s) changed · 2 impacted · 4 test(s) selected · 1 high/critical security finding(s)", out.title());
    }

    @Test
    @DisplayName("summary is cut at a line boundary below 65,535 characters with a link to the full report")
    void truncation() {
        String md = String.join("\n", Collections.nCopies(3000, "| row | with | some | text |"));
        String cut = CheckRunOutputBuilder.truncate(md, "https://ci.example/analyses/1");
        assertTrue(cut.length() <= CheckRunOutputBuilder.SUMMARY_LIMIT);
        assertTrue(cut.endsWith("…truncated — full report: https://ci.example/analyses/1\n"));
        assertEquals("short", CheckRunOutputBuilder.truncate("short", null));
    }

    @Test
    @DisplayName("annotations: introduced vulnerabilities first (failure level), then risky changed production symbols; capped")
    void annotations() {
        List<SecurityFinding> security = List.of(
                finding("STATIC_RULES", "CHANGED", "MEDIUM", "src/main/java/p/A.java", 5),
                finding("SEMGREP", "CHANGED", "HIGH", "src/main/java/p/B.java", 7),
                finding("DEPENDENCY_CHECK", "DEPENDENCY", "CRITICAL", "pom.xml", 17),
                finding("STATIC_RULES", "IMPACTED", "HIGH", "src/main/java/p/C.java", 9),     // not in this change
                finding("DEPENDENCY_CHECK", "DEPENDENCY", "HIGH", null, null));                // no location
        List<ChangedSymbol> symbols = List.of(symbol("p.X#a()", "HIGH", 60, false, false), symbol("p.X#b()", "LOW", 10, false, false),
                symbol("p.XTest#t()", "MEDIUM", 30, true, false), symbol("p.X#gone()", "HIGH", 70, false, true));
        RiskAssessment risk = RiskAssessment.builder().factors(List.of(
                Map.of("label", "Change severity", "contribution", 11.8, "explanation", "BODY_CHANGED"),
                Map.of("label", "Module criticality", "contribution", 0.0, "explanation", "none"))).build();

        var anns = CheckRunOutputBuilder.annotations(symbols, Map.of(symbols.get(0).getId(), risk), security, 150);
        assertEquals(List.of("pom.xml", "src/main/java/p/B.java", "src/main/java/p/A.java", "src/main/java/p/X.java"),
                anns.stream().map(a -> a.get("path")).toList());
        assertEquals(List.of("failure", "failure", "notice", "warning"), anns.stream().map(a -> a.get("annotation_level")).toList());
        assertEquals(10, anns.get(3).get("start_line"));
        assertEquals(20, anns.get(3).get("end_line"));
        String message = (String) anns.get(3).get("message");
        assertTrue(message.contains("Change severity +11.8: BODY_CHANGED") && !message.contains("Module criticality"), message);
        assertTrue(message.contains("not a probability"));

        assertEquals(2, CheckRunOutputBuilder.annotations(symbols, Map.of(), security, 2).size(), "capped");
    }

    @Test
    @DisplayName("annotations are sent in batches of at most 50; a failed analysis is neutral with the reason")
    void batchesAndFailure() {
        var batches = CheckRunOutputBuilder.batches(IntStream.range(0, 120).boxed().toList(), CheckRunOutputBuilder.ANNOTATIONS_PER_REQUEST);
        assertEquals(List.of(50, 50, 20), batches.stream().map(List::size).toList());

        ChangeAnalysis failed = analysis("HIGH", 60);
        failed.setStatus(ChangeAnalysis.Status.FAILED);
        failed.setErrorMessage("Both refs resolve to the same commit");
        var out = CheckRunOutputBuilder.build(failed, "", List.of(), Map.of(), List.of(), "LOW", "LOW", 150, null);
        assertEquals("neutral", out.conclusion(), "a tool problem must not block a merge");
        assertTrue(out.summary().contains("Both refs resolve to the same commit"));
        assertTrue(out.annotations().isEmpty());
    }
}
