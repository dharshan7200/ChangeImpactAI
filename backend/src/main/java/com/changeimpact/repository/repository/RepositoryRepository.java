package com.changeimpact.repository.repository;

import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.entity.Repository.IndexingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface RepositoryRepository extends JpaRepository<Repository, UUID> {

    Page<Repository> findByOwnerIdAndDeletedAtIsNull(UUID ownerId, Pageable pageable);

    Optional<Repository> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

    Optional<Repository> findByOwnerIdAndFullNameAndDeletedAtIsNull(UUID ownerId, String fullName);

    List<Repository> findByIndexingStatusAndDeletedAtIsNull(IndexingStatus status);

    @Query("SELECT r FROM Repository r WHERE r.owner.id = :ownerId AND r.deletedAt IS NULL AND " +
           "LOWER(r.fullName) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<Repository> searchByName(UUID ownerId, String query);

    boolean existsByOwnerIdAndFullNameAndDeletedAtIsNull(UUID ownerId, String fullName);
}

