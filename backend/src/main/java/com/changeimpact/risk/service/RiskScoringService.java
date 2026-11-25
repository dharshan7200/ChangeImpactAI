package com.changeimpact.risk.service;

import com.changeimpact.common.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Transparent, configurable weighted risk heuristic.
 *
 * <p><b>This is not a probability and is not statistically calibrated.</b> Each factor is normalised to
 * 0..1 with a documented formula, multiplied by its configured weight ({@code app.risk.weights}), and the
 * weighted mean is scaled to 0..100 points. Factors that do not apply to a symbol (e.g. cyclomatic
 * complexity of a field) are excluded from the denominator instead of counting as zero.
 *
 * <table>
 *   <tr><th>Factor</th><th>Normalisation</th></tr>
 *   <tr><td>changeSeverity</td><td>max severity of the change kinds (ChangeKind), +0.1 if breaking (capped at 1)</td></tr>
 *   <tr><td>directDependents</td><td>n / (n + 5), production-code dependents at depth 1</td></tr>
 *   <tr><td>transitiveDependents</td><td>n / (n + 20), all production-code dependents within the depth limit</td></tr>
 *   <tr><td>propagationDepth</td><td>deepest hop reached / max depth</td></tr>
 *   <tr><td>publicApiExposure</td><td>REST endpoint 1.0, public 0.6, protected 0.4, package 0.2, private 0</td></tr>
 *   <tr><td>complexity</td><td>c / (c + 10), cyclomatic complexity of methods/constructors</td></tr>
 *   <tr><td>moduleCriticality</td><td>first matching configured package pattern, else 0</td></tr>
 *   <tr><td>historicalChurn</td><td>n / (n + 10), commits touching the file in the scanned history</td></tr>
 *   <tr><td>testReachability</td><td>1 / (1 + t), t = test-code symbols referencing the change (static reachability, not coverage);
 *       not applicable to removed symbols and to changes of test code itself; weight 0 (informational) when
 *       changedLineCoverage is measured</td></tr>
 *   <tr><td>architectureViolations</td><td>1 if the owning class takes part in an architecture violation, else 0</td></tr>
 *   <tr><td>changedLineCoverage</td><td>1 - covered/executable changed lines, from a JaCoCo report of the exact head commit;
 *       not applicable without a report or when no changed line is executable</td></tr>
 * </table>
 */
@Service
@RequiredArgsConstructor
public class RiskScoringService {

    private final AppProperties appProperties;

    /** Everything the scorer needs about one changed symbol; null fields mean "not applicable". */
    public record Inputs(
            double changeSeverity,
            boolean breaking,
            String changeSummary,
            int directDependents,
            int transitiveDependents,
            int maxDepthReached,
            int maxDepth,
            String exposure,           // REST_ENDPOINT, PUBLIC, PROTECTED, PACKAGE_PRIVATE, PRIVATE
            Integer complexity,        // null for non-callables
            String packageName,
            String filePath,
            Long churnCommits,         // null when no history is available
            Integer testReferences,    // null = not applicable (removed symbols, test code itself)
            boolean inArchitectureViolation,
            Integer coveredChangedLines,     // null = no JaCoCo report for the head commit / no executable changed lines
            Integer executableChangedLines
    ) {
        /** Inputs without runtime coverage (the coverage factor is then not applicable). */
        public Inputs(double changeSeverity, boolean breaking, String changeSummary, int directDependents,
                      int transitiveDependents, int maxDepthReached, int maxDepth, String exposure, Integer complexity,
                      String packageName, String filePath, Long churnCommits, Integer testReferences,
                      boolean inArchitectureViolation) {
            this(changeSeverity, breaking, changeSummary, directDependents, transitiveDependents, maxDepthReached, maxDepth,
                    exposure, complexity, packageName, filePath, churnCommits, testReferences, inArchitectureViolation, null, null);
        }
    }

    public record Factor(String key, String label, Object raw, double normalized, double weight,
                         double contribution, String explanation) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("label", label);
            m.put("raw", raw);
            m.put("normalized", round(normalized, 3));
            m.put("weight", weight);
            m.put("contribution", round(contribution, 1));
            m.put("explanation", explanation);
            return m;
        }
    }

    public record Result(double score, String level, List<Factor> factors) {
        public List<Map<String, Object>> factorMaps() {
            return factors.stream().map(Factor::toMap).toList();
        }
    }

    public Result score(Inputs in) {
        AppProperties.Risk cfg = appProperties.getRisk();
        Map<String, Double> w = cfg.getWeights();
        List<Factor> raw = new ArrayList<>();

        double severity = Math.min(1.0, in.changeSeverity() + (in.breaking() ? 0.1 : 0));
        raw.add(factor("changeSeverity", "Change severity", in.changeSummary(), severity, w,
                in.changeSummary() + (in.breaking() ? " (contract-breaking)" : "")));

        raw.add(factor("directDependents", "Direct dependents", in.directDependents(),
                saturate(in.directDependents(), 5), w,
                in.directDependents() + " production symbol(s) reference it directly"));

        raw.add(factor("transitiveDependents", "Transitive dependents", in.transitiveDependents(),
                saturate(in.transitiveDependents(), 20), w,
                in.transitiveDependents() + " production symbol(s) within " + in.maxDepth() + " hop(s)"));

        raw.add(factor("propagationDepth", "Propagation depth", in.maxDepthReached(),
                in.maxDepth() == 0 ? 0 : Math.min(1.0, (double) in.maxDepthReached() / in.maxDepth()), w,
                in.maxDepthReached() == 0 ? "No dependents" : "Impact reaches " + in.maxDepthReached() + " hop(s) away"));

        double exposure = switch (in.exposure()) {
            case "REST_ENDPOINT" -> 1.0;
            case "PUBLIC" -> 0.6;
            case "PROTECTED" -> 0.4;
            case "PACKAGE_PRIVATE" -> 0.2;
            default -> 0.0;
        };
        raw.add(factor("publicApiExposure", "Public API exposure", in.exposure(), exposure, w,
                "REST_ENDPOINT".equals(in.exposure()) ? "Exposed as an HTTP endpoint" : "Visibility " + in.exposure().toLowerCase(Locale.ROOT)));

        if (in.complexity() != null) {
            raw.add(factor("complexity", "Cyclomatic complexity", in.complexity(), saturate(in.complexity(), 10), w,
                    "Cyclomatic complexity " + in.complexity()));
        }

        double criticality = criticality(in.packageName(), in.filePath());
        raw.add(factor("moduleCriticality", "Module criticality", criticality, criticality, w,
                criticality > 0 ? "Matches a configured critical module pattern" : "No critical module pattern matched"));

        if (in.churnCommits() != null) {
            raw.add(factor("historicalChurn", "Historical churn", in.churnCommits(), saturate(in.churnCommits(), 10), w,
                    "File changed in " + in.churnCommits() + " commit(s) of the scanned history"));
        }

        boolean measured = in.executableChangedLines() != null && in.executableChangedLines() > 0 && in.coveredChangedLines() != null;
        if (in.testReferences() != null) {
            String explanation = in.testReferences() == 0
                    ? ("REST_ENDPOINT".equals(in.exposure())
                        ? "No test code references this method directly (HTTP-level tests such as MockMvc are not visible to static analysis)"
                        : "No test code references this symbol (static analysis, not coverage)")
                    : in.testReferences() + " test symbol(s) reference it (static analysis, not coverage)";
            Factor f = factor("testReachability", "Missing test references", in.testReferences(),
                    1.0 / (1.0 + in.testReferences()), w, explanation);
            if (measured) {
                // measured runtime coverage supersedes the static proxy: shown for transparency, not scored
                f = new Factor(f.key(), f.label(), f.raw(), f.normalized(), 0.0, 0,
                        explanation + "; informational only, superseded by measured changed-line coverage");
            }
            raw.add(f);
        }

        if (measured) {
            int exe = in.executableChangedLines(), cov = Math.min(in.coveredChangedLines(), exe);
            raw.add(factor("changedLineCoverage", "Uncovered changed lines", cov + "/" + exe,
                    1.0 - (double) cov / exe, w,
                    "JaCoCo (head commit): " + cov + " of " + exe + " changed executable line(s) ran during the test suite"));
        }

        raw.add(factor("architectureViolations", "Architecture violations", in.inArchitectureViolation(),
                in.inArchitectureViolation() ? 1.0 : 0.0, w,
                in.inArchitectureViolation() ? "Owning class is part of an architecture violation" : "No violation involves the owning class"));

        double weightSum = raw.stream().mapToDouble(Factor::weight).sum();
        List<Factor> factors = new ArrayList<>();
        double score = 0;
        for (Factor f : raw) {
            double contribution = weightSum == 0 ? 0 : 100.0 * f.weight() * f.normalized() / weightSum;
            score += contribution;
            factors.add(new Factor(f.key(), f.label(), f.raw(), f.normalized(), f.weight(), contribution, f.explanation()));
        }
        factors.sort(Comparator.comparingDouble(Factor::contribution).reversed());
        score = round(score, 1);
        return new Result(score, level(score), factors);
    }

    public String level(double score) {
        AppProperties.Risk cfg = appProperties.getRisk();
        if (score >= cfg.getCriticalThreshold()) return "CRITICAL";
        if (score >= cfg.getHighThreshold()) return "HIGH";
        if (score >= cfg.getMediumThreshold()) return "MEDIUM";
        return "LOW";
    }

    double criticality(String packageName, String filePath) {
        List<String> subjects = new ArrayList<>();
        if (packageName != null && !packageName.isBlank()) subjects.add(packageName);
        if (filePath != null && !filePath.isBlank()) subjects.add(filePath.replace('/', '.'));
        for (AppProperties.Risk.CriticalModule m : appProperties.getRisk().getCriticalModules()) {
            Pattern pattern = Pattern.compile(m.getPattern(), Pattern.CASE_INSENSITIVE);
            if (subjects.stream().anyMatch(subject -> pattern.matcher(subject).find())) {
                return m.getCriticality();
            }
        }
        return 0;
    }

    private static Factor factor(String key, String label, Object raw, double normalized, Map<String, Double> weights,
                                 String explanation) {
        return new Factor(key, label, raw, normalized, weights.getOrDefault(key, 0.0), 0, explanation);
    }

    static double saturate(double n, double halfPoint) {
        return n <= 0 ? 0 : n / (n + halfPoint);
    }

    static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
