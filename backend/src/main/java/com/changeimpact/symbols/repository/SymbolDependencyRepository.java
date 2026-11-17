package com.changeimpact.symbols.repository;

import com.changeimpact.symbols.entity.SymbolDependency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SymbolDependencyRepository extends JpaRepository<SymbolDependency, UUID> {

    List<SymbolDependency> findByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);

    List<SymbolDependency> findBySourceSymbolId(UUID sourceSymbolId);

    List<SymbolDependency> findByTargetSymbolId(UUID targetSymbolId);

    @Query("SELECT d FROM SymbolDependency d LEFT JOIN FETCH d.sourceSymbol LEFT JOIN FETCH d.targetSymbol " +
           "WHERE d.repository.id = :repositoryId AND d.commitSha = :commitSha")
    List<SymbolDependency> findAllWithSymbolsByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);

    long countByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);
}

