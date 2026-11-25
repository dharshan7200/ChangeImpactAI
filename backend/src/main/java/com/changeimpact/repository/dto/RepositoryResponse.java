package com.changeimpact.repository.dto;

import com.changeimpact.repository.entity.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO exposing repository metadata safely without exposing
 * internal credentials, tokens, or private clone URLs.
 */
public record RepositoryResponse(
        UUID id,
        String fullName,
        String name,
        String description,
        String htmlUrl,
        String defaultBranch,
        String language,
        boolean isPrivate,
        boolean isFork,
        int starCount,
        int forkCount,
        String indexingStatus,
        Instant lastIndexedAt,
        String lastIndexedCommit,
        Instant createdAt
) {
    public static RepositoryResponse from(Repository repo) {
        return new RepositoryResponse(
                repo.getId(),
                repo.getFullName(),
                repo.getName(),
                repo.getDescription(),
                repo.getHtmlUrl(),
                repo.getDefaultBranch(),
                repo.getLanguage(),
                repo.getIsPrivate() != null && repo.getIsPrivate(),
                repo.getIsFork() != null && repo.getIsFork(),
                repo.getStarCount() != null ? repo.getStarCount() : 0,
                repo.getForkCount() != null ? repo.getForkCount() : 0,
                repo.getIndexingStatus() != null ? repo.getIndexingStatus().name() : "NOT_INDEXED",
                repo.getLastIndexedAt(),
                repo.getLastIndexedCommit(),
                repo.getCreatedAt()
        );
    }
}

