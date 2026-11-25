package com.changeimpact.securityanalysis.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("Semgrep runner: output parsing, missing binary, real scan of the bundled rules")
class SemgrepRunnerTest {

    private static final Path FIXTURE = Path.of("src/test/resources/security-fixture");

    @Test
    @DisplayName("parses real Semgrep 1.182 JSON output; rule ids are reduced to the bundled cia.* id")
    void parse() throws Exception {
        var result = SemgrepRunner.parse(Files.readString(Path.of("src/test/resources/reports/semgrep-output.json")));
        assertEquals("COMPLETED", result.status());
        assertNull(result.detail(), "no per-file errors");
        assertEquals(9, result.findings().size());
        var sql = result.findings().stream().filter(f -> f.ruleId().equals("cia.java.sql-injection.string-concat-query")).findFirst().orElseThrow();
        assertEquals("src/main/java/demo/Vulnerable.java", sql.path());
        assertEquals(17, sql.startLine());
        assertEquals("ERROR", sql.severity());
        assertEquals("SQL", sql.category());
        assertEquals("CWE-89: SQL Injection", sql.cwe());
        assertTrue(result.findings().stream().allMatch(f -> f.ruleId().startsWith("cia.java.")));
    }

    @Test
    @DisplayName("a missing binary is reported as UNAVAILABLE, never as 'no findings'; empty scope is SKIPPED")
    void unavailable() {
        var runner = new SemgrepRunner("/nonexistent/semgrep-binary", 30);
        var result = runner.scan(FIXTURE, List.of("src/main/java/demo/Vulnerable.java"));
        assertEquals("UNAVAILABLE", result.status());
        assertTrue(result.findings().isEmpty());
        assertEquals("SKIPPED", runner.scan(FIXTURE, List.of()).status());
    }

    @Test
    @DisplayName("real scan: every insecure pattern of the fixture is found, the safe variants are not")
    void realScan() {
        String cmd = System.getenv().getOrDefault("SEMGREP_COMMAND", "/tmp/claude-0/semgrep-venv/bin/semgrep");
        assumeTrue(Files.isExecutable(Path.of(cmd)), "Semgrep not installed (set SEMGREP_COMMAND to run this test)");
        var result = new SemgrepRunner(cmd, 180).scan(FIXTURE, List.of("src/main/java/demo/Vulnerable.java", "../escape/Evil.java"));
        assertEquals("COMPLETED", result.status(), String.valueOf(result.detail()));
        Map<Integer, String> byLine = result.findings().stream()
                .collect(Collectors.toMap(SemgrepRunner.Finding::startLine, SemgrepRunner.Finding::ruleId, (a, b) -> a + "," + b));
        assertEquals(Map.of(
                12, "cia.java.secrets.hardcoded-credential",
                17, "cia.java.sql-injection.string-concat-query",
                22, "cia.java.crypto.weak-hash",
                23, "cia.java.crypto.weak-cipher",
                25, "cia.java.injection.command-execution",
                26, "cia.java.deserialization.object-input-stream",
                28, "cia.java.xml.xxe-document-builder",
                36, "cia.java.random.insecure-for-security",
                37, "cia.java.logging.sensitive-value"), byLine);
        // safe variants: bind parameter (20), AES/GCM (24), XXE-hardened factory (32)
        assertFalse(byLine.containsKey(20) || byLine.containsKey(24) || byLine.containsKey(32));
    }

    @Test
    @DisplayName("real scan, other languages: every line marked 'expect:' is found with that rule, lines marked 'ok' are not")
    void realScanOtherLanguages() throws Exception {
        String cmd = System.getenv().getOrDefault("SEMGREP_COMMAND", "/tmp/claude-0/semgrep-venv/bin/semgrep");
        assumeTrue(Files.isExecutable(Path.of(cmd)), "Semgrep not installed (set SEMGREP_COMMAND to run this test)");
        List<String> files = List.of("python/app.py", "js/server.ts", "js/Comment.tsx", "php/UserController.php",
                "csharp/UsersController.cs", "go/store.go", "ruby/users_controller.rb");
        var result = new SemgrepRunner(cmd, 300).scan(FIXTURE, files);
        assertEquals("COMPLETED", result.status(), String.valueOf(result.detail()));
        Map<String, java.util.Set<String>> found = new java.util.HashMap<>();
        result.findings().forEach(f -> found.computeIfAbsent(f.path() + ":" + f.startLine(), k -> new java.util.TreeSet<>()).add(f.ruleId()));
        Map<String, String> expected = new java.util.TreeMap<>();
        java.util.List<String> safe = new java.util.ArrayList<>();
        var marker = java.util.regex.Pattern.compile("expect: (cia\\.[\\w.-]+)");
        for (String f : files) {
            List<String> lines = Files.readAllLines(FIXTURE.resolve(f));
            for (int i = 0; i < lines.size(); i++) {
                var m = marker.matcher(lines.get(i));
                if (m.find()) expected.put(f + ":" + (i + 1), m.group(1));
                else if (lines.get(i).matches(".*\\bok\\b.*")) safe.add(f + ":" + (i + 1));
            }
        }
        assertTrue(expected.size() >= 50, "fixtures cover every rule: " + expected.size());
        expected.forEach((line, rule) -> assertTrue(found.getOrDefault(line, java.util.Set.of()).contains(rule), line + " should report " + rule + ", got " + found.get(line)));
        safe.forEach(line -> assertFalse(found.containsKey(line), line + " is a safe variant but reported " + found.get(line)));
        assertEquals(expected.keySet(), found.keySet(), "no findings outside the marked lines");
        // every bundled multi-language rule is exercised by at least one fixture line
        String rules = new String(getClass().getResourceAsStream("/security/semgrep-multilang-rules.yml").readAllBytes());
        var ids = java.util.regex.Pattern.compile("- id: (cia\\.[\\w.-]+)").matcher(rules).results().map(r -> r.group(1)).collect(Collectors.toSet());
        assertEquals(ids, new java.util.HashSet<>(expected.values()), "rules without a fixture case");
    }
}
