package com.changeimpact.analysis.repository;

import com.changeimpact.analysis.entity.ChangedSymbol;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChangedSymbolRepository extends JpaRepository<ChangedSymbol, UUID> {
    List<ChangedSymbol> findByAnalysisIdOrderByRiskScoreDescQualifiedNameAsc(UUID analysisId);
}
