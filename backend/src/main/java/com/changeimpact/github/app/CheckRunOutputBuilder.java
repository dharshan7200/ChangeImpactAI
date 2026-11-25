package com.changeimpact.github.app;

import com.changeimpact.analysis.entity.ChangeAnalysis;
import com.changeimpact.analysis.entity.ChangedSymbol;
import com.changeimpact.analysis.entity.RiskAssessment;
import com.changeimpact.securityanalysis.entity.SecurityFinding;

import java.util.*;

/**
 * Builds the GitHub Check Run result from a finished analysis. Pure functions; no I/O.
 *
 * <ul>
 *   <li><b>conclusion</b>: {@code failure} when the overall risk level reaches {@code failOn}, or when the change
 *       introduces a vulnerability (Semgrep match in changed lines, known CVE in an added/changed dependency) of at
 *       least {@code failOnSecurity}; {@code neutral} for HIGH/CRITICAL risk or introduced MEDIUM+ vulnerabilities below
 *       those thresholds, and for analyses that could not run (a tool problem must not block a merge);
 *       {@code success} otherwise. Security-sensitive-area hints (STATIC_RULES) never fail a check.</li>
 *   <li><b>summary</b>: the Markdown impact report, truncated to GitHub's 65,535-character limit.</li>
 *   <li><b>annotations</b>: security findings in changed code and changed symbols of MEDIUM+ risk, on the
 *       head-commit lines; at most {@code maxAnnotations}, sent in batches of 50 (GitHub's per-request limit).</li>
 * </ul>
 */
public final class CheckRunOutputBuilder {

    public static final int SUMMARY_LIMIT = 65_535;
    public static final int ANNOTATIONS_PER_REQUEST = 50;
    private static final List<String> LEVELS = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private CheckRunOutputBuilder() {}

    public record Output(String conclusion, String title, String summary, List<Map<String, Object>> annotations) {}

    public static Output build(ChangeAnalysis analysis, String markdown, List<ChangedSymbol> symbols,
                               Map<UUID, RiskAssessment> riskBySymbol, List<SecurityFinding> security,
                               String failOn, String failOnSecurity, int maxAnnotations, String detailsUrl) {
        if (analysis.getStatus() != ChangeAnalysis.Status.COMPLETED) {
            String reason = analysis.getErrorMessage() != null ? analysis.getErrorMessage() : "unknown error";
            return new Output("neutral", "Impact analysis could not be completed",
                    truncate("ChangeImpact AI could not analyse this pull request.\n\n**Reason:** " + reason
                            + "\n\nThis does not say anything about the change itself.", detailsUrl), List.of());
        }
        String level = analysis.getRiskLevel() != null ? analysis.getRiskLevel() : "LOW";
        Map<String, Object> summary = analysis.getSummary() != null ? analysis.getSummary() : Map.of();
        String introduced = security.stream().filter(CheckRunOutputBuilder::introducedVulnerability)
                .map(SecurityFinding::getSeverity).max(Comparator.comparingInt(LEVELS::indexOf)).orElse(null);
        return new Output(conclusion(level, failOn, introduced, failOnSecurity), title(analysis, summary),
                truncate(markdown, detailsUrl), annotations(symbols, riskBySymbol, security, maxAnnotations));
    }

    /** A vulnerability this change introduces, as opposed to a hint that security-relevant code was touched. */
    static boolean introducedVulnerability(SecurityFinding f) {
        return ("SEMGREP".equals(f.getSource()) && "CHANGED".equals(f.getRelation()))
                || "DEPENDENCY_CHECK".equals(f.getSource());
    }

    static String conclusion(String level, String failOn, String introducedSeverity, String failOnSecurity) {
        int rank = LEVELS.indexOf(level);
        int sec = introducedSeverity == null ? -1 : LEVELS.indexOf(introducedSeverity);
        if (meets(rank, failOn) || meets(sec, failOnSecurity)) return "failure";
        if (rank >= LEVELS.indexOf("HIGH") || sec >= LEVELS.indexOf("MEDIUM")) return "neutral";
        return "success";
    }

    private static boolean meets(int rank, String threshold) {
        int t = threshold == null ? -1 : LEVELS.indexOf(threshold.trim().toUpperCase(Locale.ROOT)); // NEVER → -1
        return t >= 0 && rank >= t;
    }

    @SuppressWarnings("unchecked")
    static String title(ChangeAnalysis a, Map<String, Object> summary) {
        StringBuilder t = new StringBuilder("Risk ").append(a.getRiskLevel());
        if (a.getRiskScore() != null) t.append(" (").append(a.getRiskScore().stripTrailingZeros().toPlainString()).append("/100)");
        Object changed = summary.get("productionSymbolsChanged");
        if (changed != null) t.append(" · ").append(changed).append(" symbol(s) changed");
        Object impacted = summary.get("totalImpacted");
        if (impacted != null) t.append(" · ").append(impacted).append(" impacted");
        if (summary.get("tests") instanceof Map<?, ?> tests && tests.get("byCategory") instanceof Map<?, ?> byCat) {
            long selected = num(byCat.get("DIRECT")) + num(byCat.get("INDIRECT"));
            t.append(" · ").append(selected).append(" test(s) selected");
        }
        if (summary.get("security") instanceof Map<?, ?> sec && sec.get("bySeverity") instanceof Map<?, ?> bySev) {
            long severe = num(bySev.get("CRITICAL")) + num(bySev.get("HIGH"));
            if (severe > 0) t.append(" · ").append(severe).append(" high/critical security finding(s)");
        }
        return t.toString();
    }

    static List<Map<String, Object>> annotations(List<ChangedSymbol> symbols, Map<UUID, RiskAssessment> riskBySymbol,
                                                 List<SecurityFinding> security, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        // 1. security findings located in the changed lines of this PR
        security.stream()
                .filter(f -> f.getFilePath() != null && f.getStartLine() != null && f.getStartLine() > 0)
                .filter(f -> "CHANGED".equals(f.getRelation()) || "DEPENDENCY".equals(f.getRelation()))
                .filter(f -> "DEPENDENCY_CHECK".equals(f.getSource()) || !"DEPENDENCY".equals(f.getRelation())
                        || LEVELS.indexOf(f.getSeverity()) >= 0) // INFO-level dependency notes are not annotated
                .sorted(Comparator.comparingInt((SecurityFinding f) -> introducedVulnerability(f) ? 0 : 1)
                        .thenComparingInt(f -> -LEVELS.indexOf(f.getSeverity())))
                .forEach(f -> out.add(annotation(f.getFilePath(), f.getStartLine(),
                        f.getEndLine() != null && f.getEndLine() >= f.getStartLine() ? f.getEndLine() : f.getStartLine(),
                        introducedVulnerability(f) && LEVELS.indexOf(f.getSeverity()) >= LEVELS.indexOf("HIGH") ? "failure"
                                : "CRITICAL".equals(f.getSeverity()) || "HIGH".equals(f.getSeverity()) ? "warning" : "notice",
                        cut("Security · " + f.getSeverity() + " · " + f.getTitle(), 255),
                        (f.getMessage() != null ? f.getMessage() : f.getTitle())
                                + (f.getCwe() != null ? " (" + f.getCwe() + ")" : "") + " [" + f.getSource() + "]")));
        // 2. risky changed production symbols (head commit only; removed symbols have no head lines)
        symbols.stream()
                .filter(s -> !s.isTest() && s.getHeadSymbolId() != null && s.getFilePath() != null && s.getStartLine() > 0)
                .filter(s -> LEVELS.indexOf(s.getRiskLevel()) >= LEVELS.indexOf("MEDIUM"))
                .sorted(Comparator.comparing(ChangedSymbol::getRiskScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(s -> out.add(annotation(s.getFilePath(), s.getStartLine(), Math.max(s.getStartLine(), s.getEndLine()),
                        LEVELS.indexOf(s.getRiskLevel()) >= LEVELS.indexOf("HIGH") ? "warning" : "notice",
                        cut("Impact · " + s.getRiskLevel() + " risk (" + s.getRiskScore() + "/100) · " + String.join(", ", s.getChangeKinds()), 255),
                        riskMessage(s, riskBySymbol.get(s.getId())))));
        return out.size() > max ? new ArrayList<>(out.subList(0, Math.max(0, max))) : out;
    }

    private static String riskMessage(ChangedSymbol s, RiskAssessment risk) {
        StringBuilder m = new StringBuilder(s.getQualifiedName());
        if (s.isBreaking()) m.append(" — contract-breaking change");
        m.append(". ").append(s.getDirectDependents()).append(" direct / ").append(s.getTransitiveDependents())
                .append(" transitive production dependent(s).");
        if (risk != null && risk.getFactors() != null) {
            List<String> top = risk.getFactors().stream()
                    .filter(f -> f.get("contribution") instanceof Number n && n.doubleValue() > 0)
                    .limit(3)
                    .map(f -> f.get("label") + " +" + f.get("contribution") + ": " + f.get("explanation"))
                    .toList();
            if (!top.isEmpty()) m.append("\nTop risk factors:\n- ").append(String.join("\n- ", top));
        }
        m.append("\n(Heuristic score, not a probability.)");
        return m.toString();
    }

    private static Map<String, Object> annotation(String path, int start, int end, String level, String title, String message) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("path", path);
        a.put("start_line", start);
        a.put("end_line", end);
        a.put("annotation_level", level);
        a.put("title", title);
        a.put("message", cut(message, 60_000));
        return a;
    }

    /** Truncates at a line boundary below GitHub's limit and points to the full report. */
    static String truncate(String markdown, String detailsUrl) {
        String md = markdown == null ? "" : markdown;
        if (md.length() <= SUMMARY_LIMIT) return md;
        String note = "\n\n…truncated" + (detailsUrl != null ? " — full report: " + detailsUrl : "") + "\n";
        int cut = md.lastIndexOf('\n', SUMMARY_LIMIT - note.length());
        if (cut <= 0) cut = SUMMARY_LIMIT - note.length();
        return md.substring(0, cut) + note;
    }

    public static <T> List<List<T>> batches(List<T> items, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) out.add(items.subList(i, Math.min(items.size(), i + size)));
        return out;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static String cut(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max - 1) + "…";
    }
}
