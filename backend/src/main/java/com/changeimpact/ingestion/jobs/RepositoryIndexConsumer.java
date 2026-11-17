package com.changeimpact.ingestion.jobs;

import com.changeimpact.ingestion.service.IngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * RabbitMQ consumer that processes asynchronous repository indexing jobs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RepositoryIndexConsumer {

    private final IngestionService ingestionService;

    @RabbitListener(
            queues = "${app.rabbitmq.queues.repository-index}",
            containerFactory = "rabbitListenerContainerFactory"
    )
    public void handleRepositoryIndexJob(RepositoryIndexJobMessage message) {
        String correlationId = (message.jobId() != null) ? message.jobId().toString() : UUID.randomUUID().toString();
        MDC.put("jobId", correlationId);

        log.info("Received RepositoryIndexJob message for jobId: {}, repoId: {}, branch: {}",
                message.jobId(), message.repositoryId(), message.branch());

        try {
            ingestionService.executeIndexingJob(message.jobId(), message.repositoryId(), message.branch());
            log.info("Successfully finished handling RepositoryIndexJob for jobId: {}", message.jobId());
        } catch (Exception e) {
            log.error("Failed to process RepositoryIndexJob for jobId {}: {}", message.jobId(), e.getMessage(), e);
            // Re-throw so RabbitMQ can handle retry or dead-letter routing according to configuration
            throw e;
        } finally {
            MDC.remove("jobId");
        }
    }
}

