package com.changeimpact.graph.controller;

import com.changeimpact.architecture.entity.ArchitectureViolation;
import com.changeimpact.architecture.repository.ArchitectureViolationRepository;
import com.changeimpact.auth.security.UserPrincipal;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.common.response.ApiResponse;
import com.changeimpact.graph.model.CouplingMetrics;
import com.changeimpact.graph.model.DependencyPath;
import com.changeimpact.graph.model.GraphNode;
import com.changeimpact.graph.service.DependencyGraphService;
import com.changeimpact.parser.model.SymbolType;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.service.RepositoryService;
import com.changeimpact.symbols.entity.Symbol;
import com.changeimpact.symbols.repository.SymbolRepository;
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

import java.util.*;

/**
 * REST controller for deterministic static code intelligence, symbol exploration,
 * JGraphT dependency traversal, and architectural violation reporting.
 */
@RestController
@RequestMapping("/api/v1/code")
@RequiredArgsConstructor
@SecurityRequirement(name = "BearerAuth")
@Tag(name = "Code Intelligence", description = "AST symbols, dependency graphs, and architecture validation")
public class CodeIntelligenceController {

    private final com.changeimpact.repository.repository.RepositoryRepository repositoryRepository;
    private final SymbolRepository symbolRepository;
    private final DependencyGraphService dependencyGraphService;
    private final ArchitectureViolationRepository architectureViolationRepository;

    @GetMapping("/repositories/{repoId}/symbols")
    @Operation(summary = "Search repository AST symbols with optional type and query filtering")
    public ResponseEntity<ApiResponse<Page<Symbol>>> searchSymbols(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID repoId,
            @RequestParam(required = false) SymbolType symbolType,
            @RequestParam(required = false) String query,
            @PageableDefault(size = 30) Pageable pageable) {

        Repository repo = getValidatedRepository(principal.getId(), repoId);
        String commitSha = repo.getLastIndexedCommit();
        if (commitSha == null) {
            return ResponseEntity.ok(ApiResponse.success(Page.empty(pageable)));
        }

        Page<Symbol> symbols = symbolRepository.searchSymbols(repoId, commitSha, symbolType, query, pageable);
        return ResponseEntity.ok(ApiResponse.success(symbols));
    }

    @GetMapping("/symbols/{symbolId}")
    @Operation(summary = "Get detailed metadata and source location for an individual symbol")
    public ResponseEntity<ApiResponse<Symbol>> getSymbol(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID symbolId) {

        Symbol symbol = symbolRepository.findById(symbolId)
                .orElseThrow(() -> new ResourceNotFoundException("Symbol", symbolId));
        // Validate user repository access
        getValidatedRepository(principal.getId(), symbol.getRepository().getId());

        return ResponseEntity.ok(ApiResponse.success(symbol));
    }

    @GetMapping("/symbols/{symbolId}/dependencies")
    @Operation(summary = "Get forward dependencies (direct or transitive with configurable depth)")
    public ResponseEntity<ApiResponse<Collection<GraphNode>>> getDependencies(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID symbolId,
            @RequestParam(defaultValue = "1") int depth) {

        Symbol symbol = symbolRepository.findById(symbolId)
                .orElseThrow(() -> new ResourceNotFoundException("Symbol", symbolId));
        Repository repo = getValidatedRepository(principal.getId(), symbol.getRepository().getId());

        Collection<GraphNode> nodes = (depth <= 1)
                ? dependencyGraphService.getDirectDependencies(repo.getId(), symbol.getCommitSha(), symbolId)
                : dependencyGraphService.getTransitiveDependencies(repo.getId(), symbol.getCommitSha(), symbolId, depth);

        return ResponseEntity.ok(ApiResponse.success(nodes));
    }

    @GetMapping("/symbols/{symbolId}/dependents")
    @Operation(summary = "Get reverse dependents (blast radius - symbols that directly or transitively depend on this symbol)")
    public ResponseEntity<ApiResponse<Collection<GraphNode>>> getDependents(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID symbolId,
            @RequestParam(defaultValue = "1") int depth) {

        Symbol symbol = symbolRepository.findById(symbolId)
                .orElseThrow(() -> new ResourceNotFoundException("Symbol", symbolId));
        Repository repo = getValidatedRepository(principal.getId(), symbol.getRepository().getId());

        Collection<GraphNode> dependents = (depth <= 1)
                ? dependencyGraphService.getDirectDependents(repo.getId(), symbol.getCommitSha(), symbolId)
                : dependencyGraphService.getTransitiveDependents(repo.getId(), symbol.getCommitSha(), symbolId, depth);

        return ResponseEntity.ok(ApiResponse.success(dependents));
    }

    @GetMapping("/repositories/{repoId}/graph")
    @Operation(summary = "Retrieve dependency graph snapshot formatted for React Flow interactive visualization")
    public ResponseEntity<ApiResponse<DependencyGraphService.GraphSnapshotDto>> getGraphSnapshot(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID repoId,
            @RequestParam(required = false) String packageFilter) {

        Repository repo = getValidatedRepository(principal.getId(), repoId);
        String commitSha = repo.getLastIndexedCommit();
        if (commitSha == null) {
            return ResponseEntity.ok(ApiResponse.success(DependencyGraphService.GraphSnapshotDto.builder()
                    .nodes(Collections.emptyList())
                    .edges(Collections.emptyList())
                    .totalNodes(0)
                    .totalEdges(0)
                    .build()));
        }

        var snapshot = dependencyGraphService.getGraphSnapshot(repoId, commitSha, packageFilter);
        return ResponseEntity.ok(ApiResponse.success(snapshot));
    }

    @GetMapping("/repositories/{repoId}/paths")
    @Operation(summary = "Calculate the shortest directed dependency path between two symbols")
    public ResponseEntity<ApiResponse<DependencyPath>> getShortestPath(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID repoId,
            @RequestParam UUID sourceSymbolId,
            @RequestParam UUID targetSymbolId) {

        Repository repo = getValidatedRepository(principal.getId(), repoId);
        String commitSha = repo.getLastIndexedCommit();

        Optional<DependencyPath> pathOpt = dependencyGraphService.findShortestPath(
                repoId, commitSha, sourceSymbolId, targetSymbolId);

        return pathOpt.map(path -> ResponseEntity.ok(ApiResponse.success(path)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.error("No dependency path exists between the selected symbols")));
    }

    @GetMapping("/repositories/{repoId}/architecture/violations")
    @Operation(summary = "List detected architectural boundary violations and circular dependencies")
    public ResponseEntity<ApiResponse<List<ArchitectureViolation>>> getArchitectureViolations(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID repoId) {

        Repository repo = getValidatedRepository(principal.getId(), repoId);
        String commitSha = repo.getLastIndexedCommit();
        if (commitSha == null) {
            return ResponseEntity.ok(ApiResponse.success(Collections.emptyList()));
        }

        List<ArchitectureViolation> violations = architectureViolationRepository
                .findByRepositoryIdAndCommitSha(repoId, commitSha);
        return ResponseEntity.ok(ApiResponse.success(violations));
    }

    @GetMapping("/repositories/{repoId}/metrics")
    @Operation(summary = "Get coupling metrics (Ca, Ce, Instability) and identified architectural hotspots")
    public ResponseEntity<ApiResponse<List<CouplingMetrics>>> getCouplingMetrics(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID repoId) {

        Repository repo = getValidatedRepository(principal.getId(), repoId);
        String commitSha = repo.getLastIndexedCommit();
        if (commitSha == null) {
            return ResponseEntity.ok(ApiResponse.success(Collections.emptyList()));
        }

        List<CouplingMetrics> metrics = dependencyGraphService.calculateMetrics(repoId, commitSha);
        return ResponseEntity.ok(ApiResponse.success(metrics));
    }

    private Repository getValidatedRepository(UUID userId, UUID repositoryId) {
        return repositoryRepository.findByIdAndOwnerIdAndDeletedAtIsNull(repositoryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repositoryId));
    }
}
