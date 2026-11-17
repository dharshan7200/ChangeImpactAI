package com.changeimpact.repository.repository;

import com.changeimpact.repository.entity.Commit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommitRepository extends JpaRepository<Commit, UUID> {

    Optional<Commit> findByRepositoryIdAndSha(UUID repositoryId, String sha);

    Page<Commit> findByRepositoryIdOrderByCommitDateDesc(UUID repositoryId, Pageable pageable);

    boolean existsByRepositoryIdAndSha(UUID repositoryId, String sha);
}

