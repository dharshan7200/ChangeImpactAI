package com.changeimpact.symbols.repository;

import com.changeimpact.symbols.entity.SourceFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
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

    /**
     * Bulk-deletes all source files of a repository commit. Symbols and dependencies are removed by
     * the database ON DELETE CASCADE foreign keys (see V3 migration).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SourceFile f WHERE f.repository.id = :repositoryId AND f.commitSha = :commitSha")
    int deleteByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);
}

