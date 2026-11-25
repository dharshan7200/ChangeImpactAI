package com.changeimpact.securityanalysis.service;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.diff.model.FileCategory;
import com.changeimpact.diff.model.FileChange;
import com.changeimpact.diff.model.Hunk;
import com.changeimpact.diff.semantic.SymbolSnapshot;
import com.changeimpact.diff.service.GitDiffService;
import com.changeimpact.securityanalysis.entity.SecurityFinding;
import com.changeimpact.symbols.repository.SymbolDependencyRepository;
import com.changeimpact.testing.reports.entity.DependencyVulnerability;
import com.changeimpact.testing.reports.entity.QualityReport;
import com.changeimpact.testing.reports.repository.DependencyVulnerabilityRepository;
import com.changeimpact.testing.reports.repository.QualityReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.lib.Repository;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Security impact analysis of one change. Combines three independent, deterministic sources and maps each
 * finding to changed or impacted code:
 * <ol>
 *   <li>STATIC_RULES — security-sensitive code touched by the change (authentication, authorization,
 *       cryptography, SQL, sensitive data, input handling) from resolved call targets, annotations and names;</li>
 *   <li>SEMGREP — bundled pattern rules run on the changed and directly impacted files of the head commit;</li>
 *   <li>BUILD_DIFF / DEPENDENCY_CHECK — dependency changes in build files, joined with an uploaded
 *       OWASP Dependency-Check report for the head commit.</li>
 * </ol>
 * These are analysis sources, not proof that the code is (in)secure.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecurityAnalysisService {

    private final SecuritySensitivityClassifier classifier;
    private final SemgrepRunner semgrepRunner;
    private final BuildDependencyDiff buildDependencyDiff;
    private final GitDiffService gitDiffService;
    private final SymbolDependencyRepository symbolDependencyRepository;
    private final QualityReportRepository reportRepository;
    private final DependencyVulnerabilityRepository vulnerabilityRepository;
    private final AppProperties appProperties;

    /** A changed symbol as seen by security analysis. {@code current} is the head snapshot (base for removals). */
    public record ChangedInput(SymbolSnapshot current, boolean removed, List<String> changeKinds) {}

    /** A directly impacted production symbol (head commit). */
    public record ImpactedInput(String qualifiedName) {}

    public record Result(List<SecurityFinding> findings, Map<String, Object> summary) {}

    public Result analyze(UUID analysisId, UUID repositoryId, Repository git, String baseCommit, String headCommit,
                          List<FileChange> files, List<ChangedInput> changes, List<ImpactedInput> directImpacts,
                          Map<String, SymbolSnapshot> headSymbols, Map<String, SymbolSnapshot> baseSymbols) {
        List<SecurityFinding> findings = new ArrayList<>();
        Map<String, Object> summary = new LinkedHashMap<>();

        // ---- 1. security-sensitive changed code
        Set<UUID> ids = new HashSet<>();
        changes.forEach(c -> ids.add(c.current().id()));
        directImpacts.forEach(i -> Optional.ofNullable(headSymbols.get(i.qualifiedName())).ifPresent(s -> ids.add(s.id())));
        Map<UUID, List<String>> targets = callTargets(ids);

        for (ChangedInput c : changes) {
            SymbolSnapshot s = c.current();
            if (s.test()) continue;
            Map<String, SymbolSnapshot> symbols = c.removed() ? baseSymbols : headSymbols;
            for (var cl : classifier.classify(subject(s, symbols, targets))) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("matches", cl.evidence());
                ev.put("changeKinds", c.changeKinds());
                findings.add(SecurityFinding.builder().analysisId(analysisId).source("STATIC_RULES").category(cl.category())
                        .ruleId("sensitive-change." + cl.category().toLowerCase(Locale.ROOT)).severity(cl.severity())
                        .title("Security-sensitive change: " + label(cl.category()) + " in " + shortName(s.qualifiedName()))
                        .message("Changed (" + String.join(", ", c.changeKinds()) + ") code involving " + label(cl.category())
                                + ". Review: " + String.join("; ", cl.evidence()))
                        .filePath(s.filePath()).startLine(s.startLine()).endLine(s.endLine()).symbol(s.qualifiedName())
                        .relation("CHANGED").evidence(ev).build());
            }
        }

        // ---- 2. security-sensitive components affected by the change (grouped per class and category)
        Map<String, SecurityFinding> impacted = new LinkedHashMap<>();
        for (ImpactedInput i : directImpacts) {
            SymbolSnapshot s = headSymbols.get(i.qualifiedName());
            if (s == null || s.test()) continue;
            for (var cl : classifier.classify(subject(s, headSymbols, targets))) {
                String owner = ownerType(s.qualifiedName());
                String key = cl.category() + "|" + owner;
                if (impacted.containsKey(key) || impacted.size() >= 30) continue;
                impacted.put(key, SecurityFinding.builder().analysisId(analysisId).source("STATIC_RULES").category(cl.category())
                        .ruleId("affected-component." + cl.category().toLowerCase(Locale.ROOT)).severity(downgrade(cl.severity()))
                        .title("Change reaches a security-sensitive component: " + shortName(owner) + " (" + label(cl.category()) + ")")
                        .message("Directly depends on changed code. " + String.join("; ", cl.evidence()))
                        .filePath(s.filePath()).startLine(s.startLine()).endLine(s.endLine()).symbol(s.qualifiedName())
                        .relation("IMPACTED").evidence(Map.of("matches", cl.evidence())).build());
            }
        }
        findings.addAll(impacted.values());

        // ---- 3. Semgrep on changed + directly impacted source files of the head commit (Java, Python, JS/TS, PHP, C#,
        //         Go, Ruby: the languages the bundled rules cover)
        Map<String, List<Hunk>> hunks = files.stream().collect(Collectors.toMap(FileChange::path, FileChange::hunks, (a, b) -> a));
        Set<String> scope = new TreeSet<>();
        files.stream().filter(f -> !"DELETED".equals(f.changeType()))
                .filter(f -> f.category().isSource() && !f.category().isTest() && semgrepLanguage(f.path()))
                .forEach(f -> scope.add(f.path()));
        directImpacts.forEach(i -> Optional.ofNullable(headSymbols.get(i.qualifiedName()))
                .filter(s -> !s.test() && semgrepLanguage(s.filePath())).ifPresent(s -> scope.add(s.filePath())));
        SemgrepRunner.Result semgrep = runSemgrep(repositoryId, git, headCommit, new ArrayList<>(scope));
        Map<String, Object> sg = new LinkedHashMap<>();
        sg.put("status", semgrep.status());
        if (semgrep.detail() != null) sg.put("detail", semgrep.detail());
        sg.put("filesScanned", scope.size());
        sg.put("findings", semgrep.findings().size());
        summary.put("semgrep", sg);
        for (SemgrepRunner.Finding f : semgrep.findings()) {
            boolean inChange = hunks.getOrDefault(f.path(), List.of()).stream().anyMatch(h -> h.overlapsNew(f.startLine(), f.endLine()));
            SymbolSnapshot owner = innermost(headSymbols.values(), f.path(), f.startLine());
            findings.add(SecurityFinding.builder().analysisId(analysisId).source("SEMGREP").category(f.category())
                    .ruleId(f.ruleId()).severity(semgrepSeverity(f.severity())).title(f.message().length() > 500 ? f.message().substring(0, 500) : f.message())
                    .message(inChange ? "Matched in lines modified by this change" : "Matched in existing code of a changed or impacted file")
                    .filePath(f.path()).startLine(f.startLine()).endLine(f.endLine())
                    .symbol(owner != null ? owner.qualifiedName() : null).relation(inChange ? "CHANGED" : "IMPACTED").cwe(f.cwe())
                    .evidence(Map.of("tool", "semgrep", "rule", f.ruleId())).build());
        }

        // ---- 4. dependency changes and known vulnerabilities
        List<Map<String, Object>> depChanges = new ArrayList<>();
        Map<String, BuildDependencyDiff.Change> changedCoords = new HashMap<>();
        Map<String, String> coordFile = new HashMap<>();
        Map<String, Integer> coordLine = new HashMap<>();
        for (FileChange f : files) {
            if (f.category() != FileCategory.BUILD || !(f.path().endsWith("pom.xml") || f.path().contains("build.gradle"))) continue;
            try {
                String before = "ADDED".equals(f.changeType()) ? null
                        : gitDiffService.readFile(git, baseCommit, f.oldPath() != null ? f.oldPath() : f.path()).orElse(null);
                String after = "DELETED".equals(f.changeType()) ? null : gitDiffService.readFile(git, headCommit, f.path()).orElse(null);
                for (BuildDependencyDiff.Change ch : buildDependencyDiff.diff(f.path(), before, after)) {
                    changedCoords.put(ch.coordinate(), ch);
                    coordFile.put(ch.coordinate(), f.path());
                    Integer line = BuildDependencyDiff.lineOf(after, ch.coordinate());
                    if (line != null) coordLine.put(ch.coordinate(), line);
                    depChanges.add(Map.of("file", f.path(), "coordinate", ch.coordinate(), "type", ch.type(),
                            "before", String.valueOf(ch.before()), "after", String.valueOf(ch.after())));
                    if (!"REMOVED".equals(ch.type())) {
                        findings.add(SecurityFinding.builder().analysisId(analysisId).source("BUILD_DIFF").category("DEPENDENCY")
                                .ruleId("dependency." + ch.type().toLowerCase(Locale.ROOT))
                                .severity("DOWNGRADED".equals(ch.type()) ? "LOW" : "INFO")
                                .title("Dependency " + ch.type().toLowerCase(Locale.ROOT) + ": " + ch.coordinate()
                                        + (ch.before() != null ? " " + ch.before() + " → " : " ") + ch.after())
                                .message("Library behaviour is invisible to static impact analysis; run the full test suite.")
                                .filePath(f.path()).startLine(coordLine.get(ch.coordinate())).endLine(coordLine.get(ch.coordinate()))
                                .relation("DEPENDENCY").evidence(Map.of("change", ch.type())).build());
                    }
                }
            } catch (Exception e) {
                log.debug("Could not diff build file {}: {}", f.path(), e.getMessage());
            }
        }
        summary.put("dependencyChanges", depChanges);

        Optional<QualityReport> dc = reportRepository.findFirstByRepositoryIdAndCommitShaAndReportTypeOrderByCreatedAtDesc(
                repositoryId, headCommit, QualityReport.Type.DEPENDENCY_CHECK);
        if (dc.isPresent()) {
            Map<String, Long> existing = new TreeMap<>();
            for (DependencyVulnerability v : vulnerabilityRepository.findByReportId(dc.get().getId())) {
                String coord = coordinate(v.getPackageId());
                BuildDependencyDiff.Change ch = coord == null ? null : changedCoords.get(coord);
                if (ch != null && !"REMOVED".equals(ch.type())) {
                    findings.add(SecurityFinding.builder().analysisId(analysisId).source("DEPENDENCY_CHECK").category("DEPENDENCY")
                            .ruleId(v.getVulnerability()).severity(normalizeSeverity(v.getSeverity()))
                            .title("Vulnerable dependency " + ch.type().toLowerCase(Locale.ROOT) + " in this change: " + v.getVulnerability()
                                    + " in " + coord + " " + ch.after())
                            .message(v.getDescription()).relation("DEPENDENCY")
                            .filePath(coordFile.get(coord)).startLine(coordLine.get(coord)).endLine(coordLine.get(coord))
                            .cwe(v.getCwes() != null && v.getCwes().length > 0 ? v.getCwes()[0] : null)
                            .evidence(Map.of("cvss", String.valueOf(v.getCvssScore()), "package", String.valueOf(v.getPackageId()))).build());
                } else {
                    existing.merge(normalizeSeverity(v.getSeverity()), 1L, Long::sum);
                }
            }
            summary.put("dependencyCheck", Map.of("reportId", dc.get().getId(), "preExistingVulnerabilities", existing));
        } else {
            summary.put("dependencyCheck", "NO_REPORT_FOR_COMMIT");
        }

        for (SecurityFinding f : findings) { // column limits
            f.setTitle(cut(f.getTitle(), 512));
            f.setRuleId(cut(f.getRuleId(), 255));
            f.setFilePath(cut(f.getFilePath(), 1024));
            f.setSymbol(cut(f.getSymbol(), 1024));
            f.setCwe(cut(f.getCwe(), 100));
        }
        findings.sort(Comparator.comparingInt((SecurityFinding f) -> severityRank(f.getSeverity()))
                .thenComparing(f -> "CHANGED".equals(f.getRelation()) ? 0 : 1));
        summary.put("findings", findings.size());
        summary.put("bySeverity", findings.stream().collect(Collectors.groupingBy(SecurityFinding::getSeverity, TreeMap::new, Collectors.counting())));
        summary.put("byCategory", findings.stream().collect(Collectors.groupingBy(SecurityFinding::getCategory, TreeMap::new, Collectors.counting())));
        summary.put("bySource", findings.stream().collect(Collectors.groupingBy(SecurityFinding::getSource, TreeMap::new, Collectors.counting())));
        summary.put("note", "Security tools are analysis sources, not proof that the application is secure.");
        return new Result(findings, summary);
    }

    // ------------------------------------------------------------------

    private SemgrepRunner.Result runSemgrep(UUID repositoryId, Repository git, String headCommit, List<String> targets) {
        if (targets.isEmpty()) return new SemgrepRunner.Result("SKIPPED", "No Java source files in scope", List.of());
        Path dir = null;
        try {
            // private export of the head commit's Java sources (static files only; nothing is built or executed)
            dir = Files.createTempDirectory("cia-security-" + repositoryId + "-");
            gitDiffService.exportJavaSources(git, headCommit, dir, appProperties.getIngestion().getExcludedPaths());
            return semgrepRunner.scan(dir, targets);
        } catch (Exception e) {
            return new SemgrepRunner.Result("FAILED", e.getMessage(), List.of());
        } finally {
            if (dir != null) FileUtils.deleteQuietly(dir.toFile());
        }
    }

    private Map<UUID, List<String>> callTargets(Set<UUID> ids) {
        Map<UUID, List<String>> m = new HashMap<>();
        if (ids.isEmpty()) return m;
        for (Object[] row : symbolDependencyRepository.findTargetsBySourceIds(ids)) {
            m.computeIfAbsent((UUID) row[0], k -> new ArrayList<>()).add((String) row[1]);
        }
        return m;
    }

    private static SecuritySensitivityClassifier.Subject subject(SymbolSnapshot s, Map<String, SymbolSnapshot> symbols,
                                                                 Map<UUID, List<String>> targets) {
        SymbolSnapshot owner = s.parentQualifiedName() != null ? symbols.get(s.parentQualifiedName()) : null;
        return new SecuritySensitivityClassifier.Subject(s.qualifiedName(), s.simpleName(),
                owner != null ? owner.simpleName() : null, s.symbolType().name(), s.annotationText(),
                owner != null ? owner.annotationText() : null, s.parameters(), s.returnType(),
                targets.getOrDefault(s.id(), List.of()));
    }

    static SymbolSnapshot innermost(Collection<SymbolSnapshot> symbols, String path, int line) {
        return symbols.stream().filter(s -> path.equals(s.filePath()) && s.startLine() <= line && line <= s.endLine())
                .min(Comparator.comparingInt(s -> s.endLine() - s.startLine())).orElse(null);
    }

    static String coordinate(String purl) {
        if (purl == null || !purl.startsWith("pkg:maven/")) return null;
        String rest = purl.substring("pkg:maven/".length());
        int at = rest.indexOf('@');
        if (at > 0) rest = rest.substring(0, at);
        int slash = rest.indexOf('/');
        return slash < 0 ? null : rest.substring(0, slash) + ":" + rest.substring(slash + 1);
    }

    static String semgrepSeverity(String s) {
        return switch (s == null ? "" : s.toUpperCase(Locale.ROOT)) {
            case "ERROR", "HIGH" -> "HIGH";
            case "CRITICAL" -> "CRITICAL";
            case "WARNING", "MEDIUM" -> "MEDIUM";
            default -> "LOW";
        };
    }

    static String normalizeSeverity(String s) {
        String v = s == null ? "" : s.toUpperCase(Locale.ROOT);
        return switch (v) {
            case "CRITICAL", "HIGH", "MEDIUM", "LOW" -> v;
            case "MODERATE" -> "MEDIUM";
            default -> "INFO";
        };
    }

    private static String downgrade(String severity) {
        return switch (severity) { case "CRITICAL" -> "HIGH"; case "HIGH" -> "MEDIUM"; case "MEDIUM" -> "LOW"; default -> "INFO"; };
    }

    static int severityRank(String s) {
        return switch (s) { case "CRITICAL" -> 0; case "HIGH" -> 1; case "MEDIUM" -> 2; case "LOW" -> 3; default -> 4; };
    }

    private static String cut(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }

    private static String label(String category) {
        return category.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String ownerType(String qn) {
        int h = qn.indexOf('#');
        return h > 0 ? qn.substring(0, h) : qn;
    }

    private static String shortName(String qn) {
        String owner = ownerType(qn);
        String simple = owner.substring(owner.lastIndexOf('.') + 1);
        int h = qn.indexOf('#');
        return h > 0 ? simple + "#" + qn.substring(h + 1).replaceAll("\\(.*$", "()") : simple;
    }

    /** Files of a language the bundled Semgrep rules cover (templates, stylesheets and SQL scripts have no rules). */
    static boolean semgrepLanguage(String path) {
        return path != null && path.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(java|py|js|jsx|mjs|cjs|ts|tsx|mts|cts|php|cs|go|rb)$");
    }
}
