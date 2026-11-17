package com.changeimpact.repository.controller;

import com.changeimpact.auth.security.UserPrincipal;
import com.changeimpact.common.response.ApiResponse;
import com.changeimpact.repository.entity.AnalysisJob;
import com.changeimpact.repository.repository.AnalysisJobRepository;
import com.changeimpact.repository.service.RepositoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for monitoring asynchronous analysis and indexing jobs.
 */
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
@SecurityRequirement(name = "BearerAuth")
@Tag(name = "Jobs", description = "Asynchronous background job status and results")
public class JobController {

    private final RepositoryService repositoryService;
    private final AnalysisJobRepository analysisJobRepository;

    @GetMapping("/{id}")
    @Operation(summary = "Get execution status and details for an analysis job")
    public ResponseEntity<ApiResponse<AnalysisJob>> getJobStatus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {

        AnalysisJob job = repositoryService.getJobStatus(principal.getId(), id);
        return ResponseEntity.ok(ApiResponse.success(job));
    }

    @GetMapping("/repository/{repositoryId}")
    @Operation(summary = "List all analysis jobs for a specific repository")
    public ResponseEntity<ApiResponse<Page<AnalysisJob>>> listRepositoryJobs(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID repositoryId,
            @PageableDefault(size = 20) Pageable pageable) {

        // Validate repository ownership first
        repositoryService.getRepository(principal.getId(), repositoryId);

        Page<AnalysisJob> jobs = analysisJobRepository.findByRepositoryId(repositoryId, pageable);
        return ResponseEntity.ok(ApiResponse.success(jobs));
    }
}

