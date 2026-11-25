package com.changeimpact.testing.reports.repository;

import com.changeimpact.testing.reports.entity.QualityReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public interface QualityReportRepository extends JpaRepository<QualityReport, UUID> {
    Optional<QualityReport> findFirstByRepositoryIdAndCommitShaAndReportTypeOrderByCreatedAtDesc(
            UUID repositoryId, String commitSha, QualityReport.Type reportType);

    List<QualityReport> findByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId);

    Optional<QualityReport> findFirstByRepositoryIdAndReportTypeOrderByCreatedAtDesc(UUID repositoryId, QualityReport.Type reportType);
}
