package com.changeimpact.repository.repository;

import com.changeimpact.repository.entity.RepositoryIndex;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RepositoryIndexRepository extends JpaRepository<RepositoryIndex, UUID> {

    Page<RepositoryIndex> findByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId, Pageable pageable);

    Optional<RepositoryIndex> findTopByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId);

    Optional<RepositoryIndex> findByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);
}

