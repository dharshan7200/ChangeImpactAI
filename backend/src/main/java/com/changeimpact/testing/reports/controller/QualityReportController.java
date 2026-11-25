package com.changeimpact.testing.reports.controller;

import com.changeimpact.auth.security.UserPrincipal;
import com.changeimpact.common.exception.BusinessException;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.common.response.ApiResponse;
import com.changeimpact.repository.repository.RepositoryRepository;
import com.changeimpact.testing.reports.entity.QualityReport;
import com.changeimpact.testing.reports.repository.QualityReportRepository;
import com.changeimpact.testing.reports.service.ReportIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Upload of CI quality reports (JaCoCo, JUnit, PIT, OWASP Dependency-Check) for a commit.
 * Typical CI usage:
 * <pre>curl -H "Authorization: Bearer $TOKEN" -F type=JACOCO -F commitSha=$GITHUB_SHA \
 *      -F file=@target/site/jacoco/jacoco.xml $API/repositories/$ID/reports</pre>
 * JUnit: one request with a {@code -F file=@...} part per {@code target/surefire-reports/TEST-*.xml}.
 */
@RestController
@RequestMapping("/api/v1/repositories/{repositoryId}/reports")
@RequiredArgsConstructor
@SecurityRequirement(name = "BearerAuth")
@Tag(name = "Quality Reports", description = "Coverage, test results, mutation and dependency reports per commit")
public class QualityReportController {

    private final ReportIngestionService ingestionService;
    private final QualityReportRepository reportRepository;
    private final RepositoryRepository repositoryRepository;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a JACOCO / JUNIT / PIT / DEPENDENCY_CHECK report for a commit "
            + "(JUNIT accepts several 'file' parts, e.g. every Surefire TEST-*.xml)")
    public ResponseEntity<ApiResponse<QualityReport>> upload(@AuthenticationPrincipal UserPrincipal principal,
                                                             @PathVariable UUID repositoryId,
                                                             @RequestParam String type,
                                                             @RequestParam String commitSha,
                                                             @RequestParam("file") List<MultipartFile> files) throws IOException {
        QualityReport.Type reportType;
        try {
            reportType = QualityReport.Type.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_REPORT_TYPE", "type must be one of JACOCO, JUNIT, PIT, DEPENDENCY_CHECK");
        }
        if (files.isEmpty() || files.stream().allMatch(MultipartFile::isEmpty)) {
            throw new BusinessException("EMPTY_REPORT", "The uploaded report is empty");
        }
        QualityReport saved = ingestionService.ingest(principal.getId(), repositoryId, reportType, commitSha,
                files.stream().filter(f -> !f.isEmpty())
                        .map(f -> new ReportIngestionService.Upload(f.getOriginalFilename(), f)).toList());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Report ingested", saved));
    }

    @GetMapping
    @Operation(summary = "List uploaded reports (newest first)")
    public ResponseEntity<ApiResponse<List<QualityReport>>> list(@AuthenticationPrincipal UserPrincipal principal,
                                                                 @PathVariable UUID repositoryId) {
        repositoryRepository.findByIdAndOwnerIdAndDeletedAtIsNull(repositoryId, principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repositoryId));
        return ResponseEntity.ok(ApiResponse.success(reportRepository.findByRepositoryIdOrderByCreatedAtDesc(repositoryId)));
    }
}
