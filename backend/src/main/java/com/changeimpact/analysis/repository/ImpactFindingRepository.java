package com.changeimpact.analysis.repository;

import com.changeimpact.analysis.entity.ImpactFinding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ImpactFindingRepository extends JpaRepository<ImpactFinding, UUID> {

    List<ImpactFinding> findByAnalysisId(UUID analysisId);

    @Query("SELECT f FROM ImpactFinding f WHERE f.analysisId = :analysisId " +
           "AND (:changedSymbolId IS NULL OR f.changedSymbolId = :changedSymbolId) " +
           "AND (:impactType IS NULL OR f.impactType = :impactType) " +
           "AND (:includeTests = TRUE OR f.test = FALSE) " +
           "ORDER BY f.strength DESC, f.depth ASC, f.affectedQualifiedName ASC")
    Page<ImpactFinding> search(UUID analysisId, UUID changedSymbolId, String impactType, boolean includeTests, Pageable pageable);
}
