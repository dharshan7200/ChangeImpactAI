package com.changeimpact.defect;

import com.changeimpact.auth.security.UserPrincipal;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.common.response.ApiResponse;
import com.changeimpact.repository.entity.AnalysisJob;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.repository.RepositoryRepository;
import com.changeimpact.repository.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Historical defect-prediction prototype of a repository (experimental). */
@RestController
@RequestMapping("/api/v1/repositories/{repoId}/defect-model")
@RequiredArgsConstructor
@Tag(name = "Defect model", description = "Experimental just-in-time defect prediction from Git history (SZZ labels)")
public class DefectModelController {

    private final DefectModelService service;
    private final RepositoryRepository repositories;
    private final UserRepository users;

    @GetMapping
    @Operation(summary = "Stored model: status, dataset, time-ordered evaluation, coefficients and scored commits "
            + "({\"status\": \"NOT_TRAINED\"} before the first training)")
    public ApiResponse<Map<String, Object>> get(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId) {
        repository(principal, repoId);
        // "never trained" is a normal state, not an error (a 404 here showed up as a console error on every page)
        return ApiResponse.success(service.get(repoId).orElseGet(() -> Map.of("status", "NOT_TRAINED")));
    }

    @PostMapping("/train")
    @Operation(summary = "Build the dataset from history, label it with SZZ, evaluate and store the model (background job)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> train(@AuthenticationPrincipal UserPrincipal principal,
                                                                  @PathVariable UUID repoId) {
        Repository repo = repository(principal, repoId);
        AnalysisJob job = service.start(repo, users.getReferenceById(principal.getId()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success("Defect model training queued",
                Map.of("jobId", job.getId())));
    }

    private Repository repository(UserPrincipal principal, UUID repoId) {
        return repositories.findByIdAndOwnerIdAndDeletedAtIsNull(repoId, principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repoId));
    }
}
