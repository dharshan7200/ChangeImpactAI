package com.changeimpact.repository.repository;

import com.changeimpact.repository.entity.AnalysisJob;
import com.changeimpact.repository.entity.AnalysisJob.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {

    @Query("SELECT j FROM AnalysisJob j WHERE j.owner.id = :ownerId ORDER BY j.createdAt DESC")
    Page<AnalysisJob> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId, Pageable pageable);

    @Query("SELECT j FROM AnalysisJob j WHERE j.repository.id = :repositoryId AND j.status = :status")
    List<AnalysisJob> findByRepositoryIdAndStatus(UUID repositoryId, Status status);

    List<AnalysisJob> findByJobTypeAndStatus(AnalysisJob.JobType jobType, Status status);

    @Query("SELECT j FROM AnalysisJob j WHERE j.id = :id AND j.owner.id = :ownerId")
    Optional<AnalysisJob> findByIdAndOwnerId(UUID id, UUID ownerId);

    Optional<AnalysisJob> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT j FROM AnalysisJob j WHERE j.repository.id = :repositoryId " +
           "ORDER BY j.createdAt DESC")
    Page<AnalysisJob> findByRepositoryId(UUID repositoryId, Pageable pageable);

    boolean existsByIdempotencyKey(String idempotencyKey);
}

