package com.changeimpact.github.app;

import com.changeimpact.github.app.PullRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public interface PullRequestRepository extends JpaRepository<PullRequest, UUID> {
    Optional<PullRequest> findByRepositoryIdAndNumber(UUID repositoryId, Integer number);

    List<PullRequest> findByRepositoryIdOrderByUpdatedAtDesc(UUID repositoryId);

    List<PullRequest> findByRepositoryIdInAndStateOrderByUpdatedAtDesc(Collection<UUID> repositoryIds, String state);
}
