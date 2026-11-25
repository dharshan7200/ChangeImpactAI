package com.changeimpact.testing.reports.repository;

import com.changeimpact.testing.reports.entity.CoverageMethod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public interface CoverageMethodRepository extends JpaRepository<CoverageMethod, UUID> {
    List<CoverageMethod> findByReportId(UUID reportId);
}
