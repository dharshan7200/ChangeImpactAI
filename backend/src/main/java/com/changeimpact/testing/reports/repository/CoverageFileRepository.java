package com.changeimpact.testing.reports.repository;

import com.changeimpact.testing.reports.entity.CoverageFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public interface CoverageFileRepository extends JpaRepository<CoverageFile, UUID> {
    List<CoverageFile> findByReportId(UUID reportId);
}
