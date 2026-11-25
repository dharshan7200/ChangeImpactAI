package com.changeimpact.analysis.repository;

import com.changeimpact.analysis.entity.ChangeAnalysis;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChangeAnalysisRepository extends JpaRepository<ChangeAnalysis, UUID> {

    Optional<ChangeAnalysis> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<ChangeAnalysis> findByOwnerIdAndCreatedAtAfterOrderByCreatedAtDesc(UUID ownerId, java.time.Instant after);

    Page<ChangeAnalysis> findByRepositoryIdAndOwnerIdOrderByCreatedAtDesc(UUID repositoryId, UUID ownerId, Pageable pageable);

    List<ChangeAnalysis> findByRepositoryIdAndBaseRefAndHeadRefAndStatusIn(
            UUID repositoryId, String baseRef, String headRef, Collection<ChangeAnalysis.Status> statuses);
}
