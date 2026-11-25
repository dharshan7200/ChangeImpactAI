package com.changeimpact.symbols.repository;

import com.changeimpact.parser.model.SymbolType;
import com.changeimpact.symbols.entity.Symbol;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SymbolRepository extends JpaRepository<Symbol, UUID> {

    List<Symbol> findByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);

    /** Loads symbols with their source file in one query (avoids N+1 lazy loads while building the graph). */
    @Query("SELECT s FROM Symbol s LEFT JOIN FETCH s.sourceFile LEFT JOIN FETCH s.parentSymbol WHERE s.repository.id = :repositoryId AND s.commitSha = :commitSha")
    List<Symbol> findWithSourceFileByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);

    List<Symbol> findBySourceFileId(UUID sourceFileId);

    Optional<Symbol> findByRepositoryIdAndCommitShaAndQualifiedName(
            UUID repositoryId, String commitSha, String qualifiedName);

    List<Symbol> findByRepositoryIdAndCommitShaAndSymbolType(
            UUID repositoryId, String commitSha, SymbolType symbolType);

    /**
     * Searches symbols. {@code query} must never be null (pass "" to match everything): a null String
     * parameter is bound by the PostgreSQL driver as {@code bytea}, which made {@code LOWER(?)} fail.
     */
    @Query("SELECT s FROM Symbol s WHERE s.repository.id = :repositoryId AND s.commitSha = :commitSha " +
           "AND (:symbolType IS NULL OR s.symbolType = :symbolType) " +
           "AND LOWER(s.qualifiedName) LIKE CONCAT('%', LOWER(:query), '%')")
    Page<Symbol> searchSymbols(UUID repositoryId, String commitSha, SymbolType symbolType, String query, Pageable pageable);

    long countByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);

    /** True when the commit was analysed before symbol fingerprints existed (V6) and must be re-analysed. */
    boolean existsByRepositoryIdAndCommitShaAndContentHashIsNull(UUID repositoryId, String commitSha);
}

