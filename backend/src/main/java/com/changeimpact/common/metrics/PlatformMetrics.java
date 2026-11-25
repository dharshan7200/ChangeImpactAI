package com.changeimpact.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Application metrics in one place (names, tags and their allowed values). Meters are registered in Micrometer's global
 * registry, to which Spring Boot adds the Prometheus registry ({@code management.metrics.use-global-registry}), so
 * callers need no injection and unit tests without a registry record into a no-op composite.
 *
 * <p>Prometheus names: {@code changeimpact_jobs_total}, {@code changeimpact_pipeline_stage_seconds_*},
 * {@code changeimpact_analyses_risk_total}, {@code changeimpact_webhooks_total}, {@code changeimpact_checkruns_total},
 * {@code changeimpact_semgrep_seconds_*}, {@code changeimpact_watchdog_timeouts_total}, {@code changeimpact_parse_*},
 * plus the gauges of {@link QueueMetrics}. Tag values are bounded (no ids, names or messages).
 */
public final class PlatformMetrics {

    private static final Set<String> WEBHOOK_EVENTS =
            Set.of("ping", "pull_request", "push", "installation", "installation_repositories");

    /** Stage names recorded by the indexing job and the change analysis ({@code StageClock} marks). */
    static final java.util.List<String> INDEXING_STAGES =
            java.util.List.of("FETCH", "SCANNING", "HISTORY", "PARSING", "GRAPH_BUILDING", "ANALYZING", "TOTAL");
    static final java.util.List<String> ANALYSIS_STAGES = java.util.List.of("LOCK_AND_FETCH", "RESOLVING", "DIFF",
            "PARSING_BASE", "PARSING_HEAD", "SEMANTIC_DIFF", "HISTORY", "IMPACT", "PERSISTING", "TESTS", "SECURITY", "REVIEW", "TOTAL");

    private PlatformMetrics() {
    }

    /**
     * Creates every known series with value 0. Prometheus' {@code rate()}/{@code increase()} cannot see the first
     * event of a series that only appears with that event, so without this the first failure of a kind would not show.
     */
    public static void preRegister() {
        for (String type : java.util.List.of("indexing", "analysis", "architecture", "defect-model", "rag-index", "ai-review")) {
            for (String outcome : java.util.List.of("completed", "failed", "discarded")) count("changeimpact.jobs", 0, "type", type, "outcome", outcome);
        }
        INDEXING_STAGES.forEach(stage -> stageTimer("indexing", stage));
        ANALYSIS_STAGES.forEach(stage -> stageTimer("analysis", stage));
        for (String level : java.util.List.of("LOW", "MEDIUM", "HIGH", "CRITICAL")) count("changeimpact.analyses.risk", 0, "level", level);
        for (String event : new java.util.TreeSet<>(WEBHOOK_EVENTS)) {
            for (String status : java.util.List.of("ACCEPTED", "IGNORED", "FAILED", "DUPLICATE", "REJECTED")) {
                count("changeimpact.webhooks", 0, "event", event, "status", status);
            }
        }
        for (String outcome : java.util.List.of("published", "failed", "skipped")) count("changeimpact.checkruns", 0, "outcome", outcome);
        for (String kind : java.util.List.of("running", "queued", "analyses", "indexes", "repositories")) count("changeimpact.watchdog.timeouts", 0, "kind", kind);
        for (String mode : java.util.List.of("full", "incremental", "reused")) count("changeimpact.parse.runs", 0, "mode", mode);
        for (String action : java.util.List.of("parsed", "reused")) count("changeimpact.parse.files", 0, "action", action);
    }

    private static void count(String name, double amount, String... tags) {
        Counter.builder(name).tags(tags).register(registry()).increment(amount);
    }

    private static Timer stageTimer(String pipeline, String stage) {
        return Timer.builder("changeimpact.pipeline.stage")
                .description("Duration of a pipeline stage (TOTAL = whole job)")
                .tag("pipeline", pipeline).tag("stage", stage.toUpperCase(Locale.ROOT))
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(10)).maximumExpectedValue(Duration.ofHours(1))
                .register(registry());
    }

    private static MeterRegistry registry() {
        return Metrics.globalRegistry;
    }

    /** A finished background job. {@code type}: indexing | analysis; {@code outcome}: completed | failed | discarded. */
    public static void job(String type, String outcome) {
        Counter.builder("changeimpact.jobs").description("Finished background jobs")
                .tag("type", type).tag("outcome", outcome).register(registry()).increment();
    }

    /** Stage durations of one job, as collected by {@code StageClock} (incl. TOTAL). */
    public static void stageTimings(String pipeline, Map<String, Long> timingsMs) {
        timingsMs.forEach((stage, ms) -> stageTimer(pipeline, stage).record(Duration.ofMillis(Math.max(0, ms))));
    }

    /** Risk level of a completed change analysis. */
    public static void analysisRisk(String level) {
        Counter.builder("changeimpact.analyses.risk").description("Completed change analyses by risk level")
                .tag("level", level == null ? "UNKNOWN" : level).register(registry()).increment();
    }

    /** A processed webhook delivery. {@code status}: ACCEPTED, IGNORED, FAILED, DUPLICATE, REJECTED. */
    public static void webhook(String event, String status) {
        String e = event != null && WEBHOOK_EVENTS.contains(event) ? event : "other";
        Counter.builder("changeimpact.webhooks").description("GitHub webhook deliveries")
                .tag("event", e).tag("status", status).register(registry()).increment();
    }

    /** A Check Run publication. {@code outcome}: published | failed | skipped. */
    public static void checkRun(String outcome) {
        Counter.builder("changeimpact.checkruns").description("GitHub Check Run publications")
                .tag("outcome", outcome).register(registry()).increment();
    }

    /** A Semgrep run; {@code outcome} is the lower-cased result status (e.g. completed, failed, timeout, unavailable). */
    public static void semgrep(String outcome, Duration duration) {
        Timer.builder("changeimpact.semgrep").description("Semgrep runs")
                .tag("outcome", outcome).register(registry()).record(duration);
    }

    /** Items the job watchdog gave up on in one sweep, by kind (running, queued, analyses, indexes, repositories). */
    public static void watchdog(String kind, int count) {
        if (count <= 0) return;
        Counter.builder("changeimpact.watchdog.timeouts").description("Jobs and records failed by the watchdog")
                .tag("kind", kind).register(registry()).increment(count);
    }

    /** One commit analysis: {@code mode} full | incremental, and how many files were parsed and reused. */
    public static void parse(String mode, int parsedFiles, int reusedFiles) {
        Counter.builder("changeimpact.parse.runs").description("Commit analyses by mode")
                .tag("mode", mode).register(registry()).increment();
        Counter.builder("changeimpact.parse.files").description("Java files per commit analysis")
                .tag("action", "parsed").register(registry()).increment(parsedFiles);
        Counter.builder("changeimpact.parse.files").description("Java files per commit analysis")
                .tag("action", "reused").register(registry()).increment(reusedFiles);
    }
}
