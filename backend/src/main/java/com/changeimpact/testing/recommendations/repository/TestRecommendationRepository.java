package com.changeimpact.testing.recommendations.repository;

import com.changeimpact.testing.recommendations.entity.TestRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public interface TestRecommendationRepository extends JpaRepository<TestRecommendation, UUID> {
    List<TestRecommendation> findByAnalysisId(UUID analysisId);
}
