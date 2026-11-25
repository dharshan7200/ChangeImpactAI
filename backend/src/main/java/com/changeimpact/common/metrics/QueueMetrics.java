package com.changeimpact.common.metrics;

import com.changeimpact.common.config.AppProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gauges for the job queues ({@code changeimpact_queue_messages}, {@code changeimpact_queue_consumers}) and for jobs
 * waiting or running ({@code changeimpact_jobs_active}). Values are refreshed every 15 s in the background, so a
 * scrape never waits on RabbitMQ or the database; a value that cannot be read is reported as -1.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QueueMetrics implements MeterBinder {

    private static final List<String> JOB_STATES = List.of("QUEUED", "RUNNING");
    private static final List<String> JOB_TYPES = List.of("REPOSITORY_INDEX", "CHANGE_ANALYSIS", "PR_ANALYSIS", "ARCHITECTURE_HISTORY", "DEFECT_MODEL", "RAG_INDEX", "AI_REVIEW");

    private final AmqpAdmin amqpAdmin;
    private final AppProperties appProperties;
    private final JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Value("${spring.rabbitmq.listener.simple.auto-startup:true}")
    private boolean rabbitAutoStartup;

    private final Map<String, AtomicLong> messages = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> consumers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> activeJobs = new ConcurrentHashMap<>();

    private List<String> queues() {
        var q = appProperties.getRabbitmq().getQueues();
        return List.of(q.getRepositoryIndex(), q.getRepositoryIndexDlq(), q.getAnalysisJob(), q.getAnalysisJobDlq());
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void preRegisterPlatformMetrics() {
        PlatformMetrics.preRegister();
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String queue : queues()) {
            AtomicLong m = messages.computeIfAbsent(queue, k -> new AtomicLong(-1));
            AtomicLong c = consumers.computeIfAbsent(queue, k -> new AtomicLong(-1));
            Gauge.builder("changeimpact.queue.messages", m, AtomicLong::get).description("Messages waiting in a job queue")
                    .tag("queue", queue).register(registry);
            Gauge.builder("changeimpact.queue.consumers", c, AtomicLong::get).description("Consumers of a job queue")
                    .tag("queue", queue).register(registry);
        }
        for (String type : JOB_TYPES) {
            for (String state : JOB_STATES) {
                AtomicLong v = activeJobs.computeIfAbsent(type + "|" + state, k -> new AtomicLong(-1));
                Gauge.builder("changeimpact.jobs.active", v, AtomicLong::get).description("Jobs waiting or running")
                        .tag("type", type).tag("status", state).register(registry);
            }
        }
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 5_000)
    public void refresh() {
        if (rabbitAutoStartup) {
            for (String queue : queues()) {
                try {
                    QueueInformation info = amqpAdmin.getQueueInfo(queue);
                    messages.get(queue).set(info == null ? -1 : info.getMessageCount());
                    consumers.get(queue).set(info == null ? -1 : info.getConsumerCount());
                } catch (RuntimeException e) {
                    messages.get(queue).set(-1);
                    consumers.get(queue).set(-1);
                    log.debug("Queue metrics for {} unavailable: {}", queue, e.getMessage());
                }
            }
        }
        try {
            activeJobs.values().forEach(v -> v.set(0));
            jdbc.query("SELECT job_type, status, count(*) FROM analysis_jobs WHERE status IN ('QUEUED', 'RUNNING')"
                    + " GROUP BY job_type, status", rs -> {
                AtomicLong v = activeJobs.get(rs.getString(1) + "|" + rs.getString(2));
                if (v != null) v.set(rs.getLong(3));
            });
        } catch (RuntimeException e) {
            activeJobs.values().forEach(v -> v.set(-1));
            log.debug("Job metrics unavailable: {}", e.getMessage());
        }
    }
}
