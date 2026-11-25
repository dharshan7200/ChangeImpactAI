package com.changeimpact.testing.reports.parser;

import com.changeimpact.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parsers checked against trimmed copies of <b>real</b> reports produced by this project's own build
 * (JaCoCo 0.8.16, Surefire, PIT 1.30.2), plus a Dependency-Check fixture in the documented schema.
 */
@DisplayName("CI report parsers (real JaCoCo/Surefire/PIT output)")
class ReportParsersTest {

    private final ReportParsers parsers = new ReportParsers();

    private static InputStream res(String name) {
        return Objects.requireNonNull(ReportParsersTest.class.getResourceAsStream("/reports/" + name), name);
    }

    @Test
    @DisplayName("JaCoCo: per-file covered/missed lines and method counters; DOCTYPE tolerated without loading report.dtd")
    void jacoco() {
        var r = parsers.parseJacoco(res("jacoco-sample.xml"));
        var file = r.files().stream().filter(f -> f.sourcePath().equals("com/changeimpact/risk/service/RiskScoringService.java"))
                .findFirst().orElseThrow();
        assertTrue(file.coveredLines().length > 10);
        var score = r.methods().stream()
                .filter(m -> m.className().equals("com.changeimpact.risk.service.RiskScoringService") && m.methodName().equals("score"))
                .findFirst().orElseThrow();
        assertNotNull(score.firstLine());
        assertTrue(score.linesCovered() > 0);
        assertTrue(r.linesCovered() > 0, "report-level LINE counter");
    }

    @Test
    @DisplayName("JUnit: @DisplayName classnames fall back to the Java class name of the <testsuite>")
    void junit() {
        var cases = parsers.parseJunit(res("junit-sample.xml"));
        assertEquals(4, cases.size());
        assertTrue(cases.stream().allMatch(c -> c.className().equals("com.changeimpact.risk.service.RiskScoringServiceTest")),
                cases.toString());
        assertTrue(cases.stream().allMatch(c -> "PASSED".equals(c.status())));
        assertTrue(cases.stream().anyMatch(c -> c.methodName().equals("highRisk")));
    }

    @Test
    @DisplayName("JUnit: failures, errors and skips are recognised")
    void junitStatuses() {
        String xml = """
                <testsuite name="com.x.FooTest"><testcase name="ok" classname="com.x.FooTest" time="0.01"/>
                <testcase name="bad" classname="com.x.FooTest"><failure message="expected 1"/></testcase>
                <testcase name="boom" classname="com.x.FooTest"><error message="NPE"/></testcase>
                <testcase name="later" classname="com.x.FooTest"><skipped/></testcase></testsuite>""";
        var cases = parsers.parseJunit(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(java.util.List.of("PASSED", "FAILED", "ERROR", "SKIPPED"), cases.stream().map(ReportParsers.TestCaseData::status).toList());
        assertEquals("expected 1", cases.get(1).message());
    }

    @Test
    @DisplayName("PIT: mutation status, method, line, mutator and killing test")
    void pit() {
        var muts = parsers.parsePit(res("mutations-sample.xml"));
        assertEquals(25, muts.size());
        assertEquals(16, muts.stream().filter(m -> m.status().equals("KILLED")).count());
        assertEquals(8, muts.stream().filter(m -> m.status().equals("SURVIVED")).count());
        assertTrue(muts.stream().allMatch(m -> m.className().equals("com.changeimpact.risk.service.RiskScoringService")));
        assertTrue(muts.stream().filter(m -> m.status().equals("KILLED")).allMatch(m -> m.killingTest() != null));
        assertTrue(muts.stream().allMatch(m -> m.line() != null && m.mutator() != null && !m.mutator().contains(".")));
    }

    @Test
    @DisplayName("Dependency-Check: vulnerabilities with CVSS, CWE and package coordinates")
    void dependencyCheck() {
        var v = parsers.parseDependencyCheck(res("dependency-check-sample.json"));
        assertEquals(1, v.size());
        assertEquals("CVE-2022-42889", v.get(0).vulnerability());
        assertEquals("pkg:maven/org.apache.commons/commons-text@1.9", v.get(0).packageId());
        assertEquals(0, new java.math.BigDecimal("9.8").compareTo(v.get(0).cvssScore()));
        assertEquals(java.util.List.of("CWE-94"), v.get(0).cwes());
    }

    @Test
    @DisplayName("XXE: an external entity in an uploaded report is never resolved")
    void xxeIsNotResolved() {
        try {
            var r = parsers.parseJacoco(res("xxe-attack.xml"));
            // If the parser accepts the document, the entity must not have been expanded with file contents.
            assertTrue(r.files().stream().noneMatch(f -> f.sourcePath().contains("root:")));
        } catch (BusinessException expected) {
            assertTrue(expected.getMessage().contains("JaCoCo"));
        }
    }

    @Test
    @DisplayName("wrong report type is rejected with a clear error")
    void wrongType() {
        assertThrows(BusinessException.class, () -> parsers.parseJacoco(res("junit-sample.xml")));
        assertThrows(BusinessException.class, () -> parsers.parsePit(res("jacoco-sample.xml")));
        assertThrows(BusinessException.class, () -> parsers.parseDependencyCheck(
                new ByteArrayInputStream("{\"x\":1}".getBytes(StandardCharsets.UTF_8))));
    }
}
