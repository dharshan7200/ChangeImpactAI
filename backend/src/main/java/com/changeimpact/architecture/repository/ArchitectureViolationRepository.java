package com.changeimpact.architecture.repository;

import com.changeimpact.architecture.entity.ArchitectureViolation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ArchitectureViolationRepository extends JpaRepository<ArchitectureViolation, UUID> {

    List<ArchitectureViolation> findByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);

    long countByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ArchitectureViolation v WHERE v.repository.id = :repositoryId AND v.commitSha = :commitSha")
    int deleteByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);
}

