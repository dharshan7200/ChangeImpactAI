package com.changeimpact.ingestion.jobs;

import com.changeimpact.ingestion.service.IngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
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
            // The failure is already persisted on the job/repository by IngestionService.
            // Reject without requeue so the message is routed to the dead-letter queue
            // (changeimpact.repository.index.dlq) for inspection instead of looping forever.
            log.error("RepositoryIndexJob {} failed and is dead-lettered: {}", message.jobId(), e.getMessage());
            throw new AmqpRejectAndDontRequeueException("Indexing job " + message.jobId() + " failed", e);
        } finally {
            MDC.remove("jobId");
        }
    }
}

