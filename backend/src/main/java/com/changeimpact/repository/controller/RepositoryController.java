package com.changeimpact.repository.controller;

import com.changeimpact.auth.security.UserPrincipal;
import com.changeimpact.common.response.ApiResponse;
import com.changeimpact.repository.dto.ConnectRepositoryRequest;
import com.changeimpact.repository.dto.RepositoryResponse;
import com.changeimpact.repository.entity.AnalysisJob;
import com.changeimpact.repository.service.RepositoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.changeimpact.repository.entity.AnalysisJob;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.repository.AnalysisJobRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller for repository management and indexing operations.
 */
@RestController
@RequestMapping("/api/v1/repositories")
@RequiredArgsConstructor
@SecurityRequirement(name = "BearerAuth")
@Tag(name = "Repositories", description = "Repository management and indexing")
public class RepositoryController {

    private final RepositoryService repositoryService;
    private final AnalysisJobRepository analysisJobRepository;

    @PostMapping("/connect")
    @Operation(summary = "Connect a GitHub repository to the workspace")
    public ResponseEntity<ApiResponse<RepositoryResponse>> connectRepository(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ConnectRepositoryRequest request) {

        RepositoryResponse response = repositoryService.connectRepository(principal.getId(), request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Repository connected successfully", response));
    }

    @GetMapping
    @Operation(summary = "List all active repositories for current user")
    public ResponseEntity<ApiResponse<Page<RepositoryResponse>>> listRepositories(
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {

        Page<RepositoryResponse> repos = repositoryService.listRepositories(principal.getId(), pageable);
        return ResponseEntity.ok(ApiResponse.success(repos));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get repository metadata by ID")
    public ResponseEntity<ApiResponse<RepositoryResponse>> getRepository(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {

        RepositoryResponse response = repositoryService.getRepository(principal.getId(), id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{id}/index")
    @Operation(summary = "Trigger asynchronous indexing for a repository")
    public ResponseEntity<ApiResponse<Map<String, Object>>> triggerIndexing(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestParam(required = false) String branch) {

        UUID jobId = repositoryService.triggerIndexing(principal.getId(), id, branch);
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("Indexing job queued successfully", Map.of("jobId", jobId)));
    }

    @GetMapping("/{id}/index-status")
    @Operation(summary = "Get the latest indexing status of a repository")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getIndexingStatus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {

        RepositoryResponse repo = repositoryService.getRepository(principal.getId(), id);

        Page<AnalysisJob> jobs = analysisJobRepository.findByRepositoryId(id, PageRequest.of(0, 1));
        String currentStage = repo.indexingStatus() != null ? repo.indexingStatus() : "NOT_INDEXED";
        String currentMessage = "Ready for indexing";
        int progressPercent = "INDEXED".equalsIgnoreCase(repo.indexingStatus()) ? 100 : 0;
        String jobId = "";

        if (!jobs.isEmpty()) {
            AnalysisJob latest = jobs.getContent().get(0);
            currentStage = latest.getCurrentStage() != null ? latest.getCurrentStage() : latest.getStatus().name();
            currentMessage = latest.getCurrentMessage() != null ? latest.getCurrentMessage() : "";
            progressPercent = latest.getProgressPercent() != null ? latest.getProgressPercent() : 0;
            jobId = latest.getId() != null ? latest.getId().toString() : "";
        }

        Map<String, Object> statusMap = new java.util.HashMap<>();
        statusMap.put("repositoryId", repo.id());
        statusMap.put("indexingStatus", repo.indexingStatus());
        statusMap.put("currentStage", currentStage);
        statusMap.put("currentMessage", currentMessage);
        statusMap.put("progressPercent", progressPercent);
        statusMap.put("jobId", jobId);
        statusMap.put("lastIndexedAt", repo.lastIndexedAt() != null ? repo.lastIndexedAt().toString() : "");
        statusMap.put("lastIndexedCommit", repo.lastIndexedCommit() != null ? repo.lastIndexedCommit() : "");

        return ResponseEntity.ok(ApiResponse.success(statusMap));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Disconnect and soft-delete a repository")
    public ResponseEntity<ApiResponse<Void>> deleteRepository(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {

        repositoryService.deleteRepository(principal.getId(), id);
        return ResponseEntity.ok(ApiResponse.success("Repository removed successfully"));
    }
}

