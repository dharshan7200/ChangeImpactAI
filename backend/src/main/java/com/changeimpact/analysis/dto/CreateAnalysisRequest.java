package com.changeimpact.analysis.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.UUID;

/**
 * Request to compare two commits.
 *
 * @param baseRef           commit SHA (7-40 hex), branch or tag of the "before" state
 * @param headRef           commit SHA, branch or tag of the "after" state
 * @param maxDepth          impact traversal depth (default {@code app.impact.default-max-depth})
 * @param relationshipTypes relationship kinds to traverse (empty = all), e.g. ["METHOD_CALL","EXTENDS"]
 * @param useMergeBase      compare from the merge-base of base and head (pull-request semantics)
 */
public record CreateAnalysisRequest(
        @NotNull UUID repositoryId,
        @NotBlank @Pattern(regexp = "^(?!-)(?!.*\\.\\.)[A-Za-z0-9._/~^-]{1,200}$", message = "Invalid base ref") String baseRef,
        @NotBlank @Pattern(regexp = "^(?!-)(?!.*\\.\\.)[A-Za-z0-9._/~^-]{1,200}$", message = "Invalid head ref") String headRef,
        @Min(1) @Max(6) Integer maxDepth,
        List<String> relationshipTypes,
        Boolean useMergeBase
) {}
