package com.changeimpact.testing.reports.repository;

import com.changeimpact.testing.reports.entity.TestExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public interface TestExecutionRepository extends JpaRepository<TestExecution, UUID> {
    List<TestExecution> findByReportId(UUID reportId);
}
