package com.changeimpact.ingestion.jobs;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.ingestion.service.IngestionService;
import com.changeimpact.repository.entity.AnalysisJob;
import com.changeimpact.repository.repository.AnalysisJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Publishes repository indexing job messages to RabbitMQ, or executes them
 * directly in the background via TaskExecutor when RabbitMQ is unavailable or disabled.
 */
@Component
@Slf4j
public class RepositoryIndexJobPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final AppProperties appProperties;
    private final ObjectProvider<IngestionService> ingestionServiceProvider;
    private final AnalysisJobRepository analysisJobRepository;
    private final TaskExecutor taskExecutor;

    @Value("${spring.rabbitmq.listener.simple.auto-startup:true}")
    private boolean rabbitAutoStartup;

    public RepositoryIndexJobPublisher(RabbitTemplate rabbitTemplate,
                                       AppProperties appProperties,
                                       ObjectProvider<IngestionService> ingestionServiceProvider,
                                       AnalysisJobRepository analysisJobRepository,
                                       @Qualifier("applicationTaskExecutor") @Lazy TaskExecutor taskExecutor) {
        this.rabbitTemplate = rabbitTemplate;
        this.appProperties = appProperties;
        this.ingestionServiceProvider = ingestionServiceProvider;
        this.analysisJobRepository = analysisJobRepository;
        this.taskExecutor = taskExecutor;
    }

    public void publishRepositoryIndexJob(UUID jobId, UUID repositoryId, String branch) {
        publishRepositoryIndexJob(jobId, repositoryId, branch, null);
    }

    public void publishRepositoryIndexJob(UUID jobId, UUID repositoryId, String branch, String commitSha) {
        if (!rabbitAutoStartup) {
            log.info("RabbitMQ consumers disabled (standalone mode). Executing RepositoryIndexJob {} for repo {} asynchronously.",
                    jobId, repositoryId);
            runAsync(jobId, repositoryId, branch);
            return;
        }

        RepositoryIndexJobMessage message = new RepositoryIndexJobMessage(
                jobId,
                repositoryId,
                branch,
                commitSha
        );

        String exchange = appProperties.getRabbitmq().getExchanges().getMain();
        String routingKey = "repository.index";

        log.info("Publishing RepositoryIndexJob to exchange '{}' with routingKey '{}' for repo: {}, job: {}",
                exchange, routingKey, repositoryId, jobId);

        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, message);
        } catch (Exception e) {
            log.warn("RabbitMQ publish failed ({}); falling back to local async execution for repo {} job {}",
                    e.getMessage(), repositoryId, jobId);
            runAsync(jobId, repositoryId, branch);
        }
    }

    private void runAsync(UUID jobId, UUID repositoryId, String branch) {
        taskExecutor.execute(() -> {
            try {
                IngestionService ingestionService = ingestionServiceProvider.getIfAvailable();
                if (ingestionService != null) {
                    ingestionService.executeIndexingJob(jobId, repositoryId, branch);
                } else {
                    log.error("IngestionService bean not available to execute indexing job {}", jobId);
                }
            } catch (Exception e) {
                log.error("Async execution of RepositoryIndexJob failed for job {}: {}", jobId, e.getMessage(), e);
            }
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStuckJobs() {
        if (!rabbitAutoStartup) {
            try {
                List<AnalysisJob> queuedJobs = analysisJobRepository.findByJobTypeAndStatus(
                        AnalysisJob.JobType.REPOSITORY_INDEX, AnalysisJob.Status.QUEUED);
                if (!queuedJobs.isEmpty()) {
                    log.info("Found {} stuck QUEUED repository indexing jobs on startup. Triggering async processing...", queuedJobs.size());
                    for (AnalysisJob job : queuedJobs) {
                        UUID jobId = job.getId();
                        UUID repoId = job.getRepository() != null ? job.getRepository().getId() : null;
                        String branch = null;
                        if (job.getPayload() != null && job.getPayload().get("branch") != null) {
                            branch = String.valueOf(job.getPayload().get("branch"));
                        } else if (job.getRepository() != null) {
                            branch = job.getRepository().getDefaultBranch();
                        }
                        if (repoId != null) {
                            log.info("Auto-recovering stuck indexing job {} for repo {} (branch: {})", jobId, repoId, branch);
                            runAsync(jobId, repoId, branch);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to recover stuck indexing jobs on startup: {}", e.getMessage(), e);
            }
        }
    }
}
