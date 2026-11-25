package com.changeimpact.common.logging;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Measures consecutive pipeline stages of one job (indexing, change analysis). Bound to the worker thread while the
 * job runs; {@link #next(String)} closes the current stage. Durations are logged per job and exported as metrics.
 */
public final class StageClock {

    private static final ThreadLocal<StageClock> CURRENT = new ThreadLocal<>();

    private final String job;
    private final Map<String, Long> durationsMs = new LinkedHashMap<>();
    private String stage;
    private long stageStart;
    private final long jobStart;

    private StageClock(String job, String firstStage) {
        this.job = job;
        this.stage = firstStage;
        this.stageStart = this.jobStart = System.nanoTime();
    }

    public static StageClock start(String job, String firstStage) {
        StageClock c = new StageClock(job, firstStage);
        CURRENT.set(c);
        return c;
    }

    /** Ends the running stage of the current thread's job (no-op when no job is being timed). */
    public static void mark(String nextStage) {
        StageClock c = CURRENT.get();
        if (c != null) c.next(nextStage);
    }

    public void next(String nextStage) {
        long now = System.nanoTime();
        durationsMs.merge(stage, (now - stageStart) / 1_000_000, Long::sum);
        stage = nextStage;
        stageStart = now;
    }

    /** Closes the last stage, unbinds the clock and returns stage → milliseconds (plus TOTAL). */
    public Map<String, Long> finish() {
        next("END");
        durationsMs.remove("END");
        durationsMs.put("TOTAL", (System.nanoTime() - jobStart) / 1_000_000);
        CURRENT.remove();
        return durationsMs;
    }

    public String job() {
        return job;
    }
}
