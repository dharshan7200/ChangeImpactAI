package com.changeimpact.architecture;

import com.changeimpact.architecture.service.ArchitectureRuleService;
import com.changeimpact.architecture.service.ArchitectureRuleService.RuleRequest;
import com.changeimpact.auth.security.UserPrincipal;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.common.response.ApiResponse;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.repository.RepositoryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Architecture rules of a repository (owner) and the global default rules (administrators). */
@RestController
@RequiredArgsConstructor
@Tag(name = "Architecture rules", description = "Rule management: create, edit, switch off, preview and re-evaluate")
public class ArchitectureRuleController {

    private final ArchitectureRuleService service;
    private final RepositoryRepository repositories;

    public record ActiveRequest(Boolean active) {}

    @GetMapping("/api/v1/repositories/{repoId}/architecture/rules")
    @Operation(summary = "Rules visible to the repository: global defaults and its own, with whether each is in effect and its violations at the last indexed commit")
    public ApiResponse<List<ArchitectureRuleService.RuleView>> list(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId) {
        return ApiResponse.success(service.list(repository(principal, repoId)));
    }

    @PostMapping("/api/v1/repositories/{repoId}/architecture/rules")
    @Operation(summary = "Create a repository rule (CIRCULAR_DEPENDENCY, LAYER_VIOLATION/FORBIDDEN_DEPENDENCY, LAYERED_ARCHITECTURE, ONLY_ACCESSED_BY)")
    public ResponseEntity<ApiResponse<ArchitectureRuleService.RuleView>> create(@AuthenticationPrincipal UserPrincipal principal,
                                                                                @PathVariable UUID repoId, @RequestBody RuleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(repository(principal, repoId), req)));
    }

    @PutMapping("/api/v1/repositories/{repoId}/architecture/rules/{ruleId}")
    @Operation(summary = "Update a repository rule (fields left out keep their value)")
    public ApiResponse<ArchitectureRuleService.RuleView> update(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId,
                                                                @PathVariable UUID ruleId, @RequestBody RuleRequest req) {
        return ApiResponse.success(service.update(repository(principal, repoId), ruleId, req));
    }

    @DeleteMapping("/api/v1/repositories/{repoId}/architecture/rules/{ruleId}")
    @Operation(summary = "Delete a repository rule")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId, @PathVariable UUID ruleId) {
        service.delete(repository(principal, repoId), ruleId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/v1/repositories/{repoId}/architecture/rules/{ruleId}/active")
    @Operation(summary = "Switch a rule on or off for this repository (global rules: per-repository override)")
    public ApiResponse<ArchitectureRuleService.RuleView> setActive(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId,
                                                                   @PathVariable UUID ruleId, @RequestBody ActiveRequest req) {
        if (req == null || req.active() == null) throw new com.changeimpact.common.exception.BusinessException("INVALID_RULE", "active (true/false) is required");
        return ApiResponse.success(service.setActive(repository(principal, repoId), ruleId, req.active()));
    }

    @PostMapping("/api/v1/repositories/{repoId}/architecture/rules/preview")
    @Operation(summary = "Violations a draft rule would report on the last indexed commit (nothing is stored)")
    public ApiResponse<ArchitectureRuleService.Preview> preview(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId,
                                                                @RequestBody RuleRequest req) {
        return ApiResponse.success(service.preview(repository(principal, repoId), req));
    }

    @PostMapping("/api/v1/repositories/{repoId}/architecture/rules/evaluate")
    @Operation(summary = "Recompute the stored violations of the last indexed commit with the rules now in effect")
    public ApiResponse<ArchitectureRuleService.Evaluation> evaluate(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId) {
        return ApiResponse.success(service.evaluate(repository(principal, repoId)));
    }

    // ------------------------------------------------------------------ administrators: global default rules

    @GetMapping("/api/v1/admin/architecture/rules")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Global default rules (apply to every repository unless switched off there)")
    public ApiResponse<List<ArchitectureRuleService.RuleView>> listGlobal() {
        return ApiResponse.success(service.listGlobal());
    }

    @PostMapping("/api/v1/admin/architecture/rules")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ArchitectureRuleService.RuleView>> createGlobal(@RequestBody RuleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.createGlobal(req)));
    }

    @PutMapping("/api/v1/admin/architecture/rules/{ruleId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ArchitectureRuleService.RuleView> updateGlobal(@PathVariable UUID ruleId, @RequestBody RuleRequest req) {
        return ApiResponse.success(service.updateGlobal(ruleId, req));
    }

    @DeleteMapping("/api/v1/admin/architecture/rules/{ruleId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteGlobal(@PathVariable UUID ruleId) {
        service.deleteGlobal(ruleId);
        return ResponseEntity.noContent().build();
    }

    private Repository repository(UserPrincipal principal, UUID repoId) {
        return repositories.findByIdAndOwnerIdAndDeletedAtIsNull(repoId, principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repoId));
    }
}
