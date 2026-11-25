package com.changeimpact.repository.repository;

import com.changeimpact.repository.entity.RepositoryBranch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RepositoryBranchRepository extends JpaRepository<RepositoryBranch, UUID> {

    List<RepositoryBranch> findByRepositoryId(UUID repositoryId);

    Optional<RepositoryBranch> findByRepositoryIdAndName(UUID repositoryId, String name);

    Optional<RepositoryBranch> findByRepositoryIdAndIsDefaultTrue(UUID repositoryId);
}

