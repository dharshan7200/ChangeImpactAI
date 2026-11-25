package com.changeimpact.testing.reports.repository;

import com.changeimpact.testing.reports.entity.MutationResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public interface MutationResultRepository extends JpaRepository<MutationResult, UUID> {
    List<MutationResult> findByReportId(UUID reportId);
}
