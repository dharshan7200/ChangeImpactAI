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

    /** Reference counts grouped by resolution status: rows of [ResolutionStatus, Long]. */
    @Query("SELECT d.resolutionStatus, COUNT(d) FROM SymbolDependency d " +
           "WHERE d.repository.id = :repositoryId AND d.commitSha = :commitSha GROUP BY d.resolutionStatus")
    List<Object[]> countByResolutionStatus(UUID repositoryId, String commitSha);

    @Query(value = "SELECT d FROM SymbolDependency d JOIN FETCH d.sourceSymbol s LEFT JOIN FETCH s.sourceFile " +
                   "WHERE d.repository.id = :repositoryId AND d.commitSha = :commitSha AND d.resolutionStatus = :status",
           countQuery = "SELECT COUNT(d) FROM SymbolDependency d " +
                   "WHERE d.repository.id = :repositoryId AND d.commitSha = :commitSha AND d.resolutionStatus = :status")
    org.springframework.data.domain.Page<SymbolDependency> findByStatus(
            UUID repositoryId, String commitSha, com.changeimpact.parser.model.ResolutionStatus status,
            org.springframework.data.domain.Pageable pageable);

    /** Rows of [sourceQualifiedName, targetQualifiedName] for the given relationship kinds (e.g. supertypes). */
    @Query("SELECT d.sourceSymbol.qualifiedName, d.targetQualifiedName FROM SymbolDependency d " +
           "WHERE d.repository.id = :repositoryId AND d.commitSha = :commitSha AND d.dependencyType IN :types")
    List<Object[]> findNamesByTypes(UUID repositoryId, String commitSha,
                                    java.util.Collection<com.changeimpact.parser.model.DependencyType> types);

    /** Rows of [sourceSymbolId, targetQualifiedName] for the given source symbols (all resolution states). */
    @Query("SELECT d.sourceSymbol.id, d.targetQualifiedName FROM SymbolDependency d WHERE d.sourceSymbol.id IN :sourceIds")
    List<Object[]> findTargetsBySourceIds(java.util.Collection<UUID> sourceIds);
}
