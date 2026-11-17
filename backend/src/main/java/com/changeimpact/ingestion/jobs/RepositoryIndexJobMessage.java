package com.changeimpact.ingestion.jobs;

import java.io.Serializable;
import java.util.UUID;

/**
 * Message payload published to RabbitMQ to request repository indexing.
 */
public record RepositoryIndexJobMessage(
        UUID jobId,
        UUID repositoryId,
        String branch,
        String commitSha
) implements Serializable {}

