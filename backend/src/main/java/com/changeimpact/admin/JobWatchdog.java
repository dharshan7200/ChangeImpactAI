package com.changeimpact.admin;

import com.changeimpact.analysis.service.ChangeAnalysisEvent;
import com.changeimpact.common.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Fails jobs that can no longer finish: RUNNING longer than {@code app.ingestion.job-timeout-minutes} (crashed or hung
 * worker) or QUEUED longer than {@code queued-timeout-minutes} (lost message). Change analyses and repository index
 * states follow their job, and pull-request Check Runs of timed-out analyses are completed (neutral) via the
 * analysis FAILED event.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JobWatchdog {

    private final AppProperties appProperties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher events;

    @Scheduled(fixedDelayString = "${app.ingestion.watchdog-interval-ms:300000}", initialDelay = 60_000)
    public void scheduled() {
        Map<String, Object> result = sweep();
        if (result.values().stream().anyMatch(v -> v instanceof Number n && n.intValue() > 0)) {
            log.warn("Job watchdog: {}", result);
        }
    }

    public Map<String, Object> sweep() {
        int running = appProperties.getIngestion().getJobTimeoutMinutes();
        int queued = appProperties.getIngestion().getQueuedTimeoutMinutes();
        Map<String, Object> out = new LinkedHashMap<>();
        List<UUID> failedAnalyses = new ArrayList<>();
        transactionTemplate.executeWithoutResult(s -> {
            out.put("runningJobsTimedOut", jdbc.update("""
                    UPDATE analysis_jobs SET status = 'FAILED', completed_at = now(),
                           error_message = 'Timed out: running longer than ' || ? || ' minutes'
                     WHERE status = 'RUNNING' AND COALESCE(started_at, created_at) < now() - make_interval(mins => ?)""",
                    running, running));
            out.put("queuedJobsTimedOut", jdbc.update("""
                    UPDATE analysis_jobs SET status = 'FAILED', completed_at = now(),
                           error_message = 'Timed out: still queued after ' || ? || ' minutes'
                     WHERE status = 'QUEUED' AND created_at < now() - make_interval(mins => ?)""", queued, queued));
            failedAnalyses.addAll(jdbc.queryForList("""
                    UPDATE change_analyses a SET status = 'FAILED', completed_at = now(),
                           error_message = COALESCE((SELECT j.error_message FROM analysis_jobs j WHERE j.id = a.job_id), 'Timed out')
                     WHERE a.status IN ('QUEUED', 'RUNNING')
                       AND (EXISTS (SELECT 1 FROM analysis_jobs j WHERE j.id = a.job_id AND j.status = 'FAILED')
                            OR (a.job_id IS NULL AND a.created_at < now() - make_interval(mins => ?)))
                    RETURNING a.id""", UUID.class, running));
            out.put("analysesFailed", failedAnalyses.size());
            out.put("indexesFailed", jdbc.update("""
                    UPDATE repository_indexes SET status = 'FAILED', completed_at = now(), error_message = 'Timed out'
                     WHERE status = 'RUNNING' AND started_at < now() - make_interval(mins => ?)""", running));
            out.put("repositoriesReset", jdbc.update("""
                    UPDATE repositories r SET indexing_status = 'FAILED'
                     WHERE r.indexing_status = 'INDEXING'
                       AND NOT EXISTS (SELECT 1 FROM analysis_jobs j WHERE j.repository_id = r.id
                                        AND j.job_type = 'REPOSITORY_INDEX' AND j.status IN ('QUEUED', 'RUNNING'))"""));
        });
        // after commit: pull-request Check Runs of these analyses are completed as neutral with the reason
        failedAnalyses.forEach(id -> events.publishEvent(new ChangeAnalysisEvent(id, ChangeAnalysisEvent.Phase.FAILED)));
        Map<String, String> kinds = Map.of("runningJobsTimedOut", "running", "queuedJobsTimedOut", "queued",
                "analysesFailed", "analyses", "indexesFailed", "indexes", "repositoriesReset", "repositories");
        out.forEach((key, value) -> {
            if (value instanceof Number n && kinds.containsKey(key)) {
                com.changeimpact.common.metrics.PlatformMetrics.watchdog(kinds.get(key), n.intValue());
            }
        });
        return out;
    }
}
