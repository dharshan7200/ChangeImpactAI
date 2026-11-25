package com.changeimpact.architecture.twin;

import com.changeimpact.architecture.twin.TwinModel.*;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/** Architecture digital twin of a repository: snapshots, hotspots, trends, comparisons and history backfill. */
@RestController
@RequestMapping("/api/v1/repositories/{repoId}/architecture")
@RequiredArgsConstructor
@Tag(name = "Architecture twin", description = "Structural model of every analysed commit")
public class ArchitectureTwinController {

    private final ArchitectureTwinService twin;
    private final ArchitectureHistoryService history;
    private final RepositoryRepository repositories;
    private final UserRepository users;
    private final JdbcTemplate jdbc;

    public record AnalysedCommit(String sha, Instant commitDate, String message, boolean snapshot) {}

    public record BackfillRequest(Integer commits) {}

    @GetMapping("/commits")
    @Operation(summary = "Analysed commits (the twin has a snapshot or can build one for each)")
    public ApiResponse<List<AnalysedCommit>> commits(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId) {
        repository(principal, repoId);
        List<AnalysedCommit> out = jdbc.query("""
                SELECT f.commit_sha, max(c.commit_date), max(c.message), bool_or(a.id IS NOT NULL)
                  FROM source_files f
                  LEFT JOIN commits c ON c.repository_id = f.repository_id AND c.sha = f.commit_sha
                  LEFT JOIN architecture_snapshots a ON a.repository_id = f.repository_id AND a.commit_sha = f.commit_sha
                 WHERE f.repository_id = ?
                 GROUP BY f.commit_sha
                 ORDER BY max(c.commit_date) DESC NULLS LAST, max(f.created_at) DESC
                 LIMIT 100""", (rs, i) -> new AnalysedCommit(rs.getString(1),
                rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant(),
                rs.getString(3) == null ? null : rs.getString(3).strip().lines().findFirst().orElse(""),
                rs.getBoolean(4)), repoId);
        return ApiResponse.success(out);
    }

    @GetMapping("/snapshot")
    @Operation(summary = "Package structure of an analysed commit (default: last indexed commit)")
    public ApiResponse<Snapshot> snapshot(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId,
                                          @RequestParam(required = false) String commit) {
        Repository repo = repository(principal, repoId);
        String sha = commit(repo, commit);
        return ApiResponse.success(twin.snapshot(repoId, sha).orElseThrow(() -> notAnalysed(sha)));
    }

    @GetMapping("/hotspots")
    @Operation(summary = "Files with churn × complexity (hotspots), size, fan-in and coverage")
    public ApiResponse<Hotspots> hotspots(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId,
                                          @RequestParam(required = false) String commit) {
        Repository repo = repository(principal, repoId);
        String sha = commit(repo, commit);
        return ApiResponse.success(twin.hotspots(repoId, sha).orElseThrow(() -> notAnalysed(sha)));
    }

    @GetMapping("/trend")
    @Operation(summary = "Summary metrics of the analysed commits, oldest first (at most 40)")
    public ApiResponse<List<TrendPoint>> trend(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId) {
        repository(principal, repoId);
        return ApiResponse.success(twin.trend(repoId));
    }

    @GetMapping("/compare")
    @Operation(summary = "Architecture drift between two analysed commits")
    public ApiResponse<Comparison> compare(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId,
                                           @RequestParam String base, @RequestParam String head) {
        Repository repo = repository(principal, repoId);
        String b = commit(repo, base), h = commit(repo, head);
        Snapshot sb = twin.snapshot(repoId, b).orElseThrow(() -> notAnalysed(b));
        Snapshot sh = twin.snapshot(repoId, h).orElseThrow(() -> notAnalysed(h));
        return ApiResponse.success(twin.compare(repoId, sb, sh));
    }

    @PostMapping("/backfill")
    @Operation(summary = "Analyse up to 20 commits spread over the default branch history (background job)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> backfill(@AuthenticationPrincipal UserPrincipal principal,
                                                                     @PathVariable UUID repoId,
                                                                     @RequestBody(required = false) BackfillRequest request) {
        Repository repo = repository(principal, repoId);
        int commits = request == null || request.commits() == null ? 10 : request.commits();
        AnalysisJob job = history.start(repo, users.getReferenceById(principal.getId()), commits);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success("Architecture history queued",
                Map.of("jobId", job.getId(), "commits", Math.max(2, Math.min(ArchitectureHistoryService.MAX_COMMITS, commits)))));
    }

    private Repository repository(UserPrincipal principal, UUID repoId) {
        return repositories.findByIdAndOwnerIdAndDeletedAtIsNull(repoId, principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repoId));
    }

    /** The requested analysed commit, or the last indexed one, or the newest analysed one. */
    private String commit(Repository repo, String ref) {
        if (ref != null && !ref.isBlank()) {
            return twin.resolveAnalysedCommit(repo.getId(), ref.trim()).orElseThrow(() -> notAnalysed(ref.trim()));
        }
        if (repo.getLastIndexedCommit() != null && twin.resolveAnalysedCommit(repo.getId(), repo.getLastIndexedCommit()).isPresent()) {
            return repo.getLastIndexedCommit();
        }
        return twin.analysedCommits(repo.getId(), 1).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No analysed commit yet — index the repository first"));
    }

    private static ResourceNotFoundException notAnalysed(String ref) {
        return new ResourceNotFoundException("Commit " + ref + " is not analysed (index it, analyse a change with it, or run the history backfill)");
    }
}
