package com.changeimpact.ingestion.jobs;

import com.changeimpact.common.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publishes repository indexing job messages to RabbitMQ.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RepositoryIndexJobPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final AppProperties appProperties;

    public void publishRepositoryIndexJob(UUID jobId, UUID repositoryId, String branch) {
        publishRepositoryIndexJob(jobId, repositoryId, branch, null);
    }

    public void publishRepositoryIndexJob(UUID jobId, UUID repositoryId, String branch, String commitSha) {
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

        rabbitTemplate.convertAndSend(exchange, routingKey, message);
    }
}

