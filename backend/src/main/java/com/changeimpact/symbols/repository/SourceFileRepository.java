package com.changeimpact.symbols.repository;

import com.changeimpact.symbols.entity.SourceFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SourceFileRepository extends JpaRepository<SourceFile, UUID> {

    List<SourceFile> findByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);

    Optional<SourceFile> findByRepositoryIdAndCommitShaAndRelativePath(
            UUID repositoryId, String commitSha, String relativePath);

    long countByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);
}

