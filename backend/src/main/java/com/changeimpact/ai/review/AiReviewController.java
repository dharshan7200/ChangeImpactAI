package com.changeimpact.ai.review;

import com.changeimpact.analysis.entity.ChangeAnalysis;
import com.changeimpact.analysis.repository.ChangeAnalysisRepository;
import com.changeimpact.auth.security.UserPrincipal;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** AI review of a change analysis (Phase 4): requested on demand, validated against the evidence. */
@RestController
@RequestMapping("/api/v1/analyses/{analysisId}/ai-review")
@RequiredArgsConstructor
@Tag(name = "AI review", description = "Gemini review of a completed analysis, grounded in and validated against its evidence")
public class AiReviewController {

    private final AiReviewService service;
    private final ChangeAnalysisRepository analyses;

    @PostMapping
    @Operation(summary = "Start an AI review (background job AI_REVIEW)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> start(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID analysisId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("AI review queued", service.start(owned(principal, analysisId), principal.getId())));
    }

    @GetMapping
    @Operation(summary = "Latest AI review: findings (VERIFIED / UNVERIFIED), test suggestions, evidence, model, tokens, latency")
    public ApiResponse<Map<String, Object>> latest(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID analysisId) {
        owned(principal, analysisId);
        return ApiResponse.success(service.latest(analysisId));
    }

    private ChangeAnalysis owned(UserPrincipal principal, UUID id) {
        return analyses.findByIdAndOwnerId(id, principal.getId()).orElseThrow(() -> new ResourceNotFoundException("Analysis", id));
    }
}
