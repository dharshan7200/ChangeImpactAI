package com.changeimpact.analysis.repository;

import com.changeimpact.analysis.entity.RiskAssessment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, UUID> {
    List<RiskAssessment> findByAnalysisId(UUID analysisId);
}
