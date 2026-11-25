package com.changeimpact.securityanalysis.repository;

import com.changeimpact.securityanalysis.entity.SecurityFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public interface SecurityFindingRepository extends JpaRepository<SecurityFinding, UUID> {
    List<SecurityFinding> findByAnalysisId(UUID analysisId);

    List<SecurityFinding> findByAnalysisIdIn(Collection<UUID> analysisIds);
}
