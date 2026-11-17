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

    List<Symbol> findBySourceFileId(UUID sourceFileId);

    Optional<Symbol> findByRepositoryIdAndCommitShaAndQualifiedName(
            UUID repositoryId, String commitSha, String qualifiedName);

    List<Symbol> findByRepositoryIdAndCommitShaAndSymbolType(
            UUID repositoryId, String commitSha, SymbolType symbolType);

    @Query("SELECT s FROM Symbol s WHERE s.repository.id = :repositoryId AND s.commitSha = :commitSha " +
           "AND (:symbolType IS NULL OR s.symbolType = :symbolType) " +
           "AND (:query IS NULL OR LOWER(s.qualifiedName) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<Symbol> searchSymbols(UUID repositoryId, String commitSha, SymbolType symbolType, String query, Pageable pageable);

    long countByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);
}

