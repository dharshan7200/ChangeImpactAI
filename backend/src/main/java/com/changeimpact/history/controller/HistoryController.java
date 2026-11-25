package com.changeimpact.history.controller;

import com.changeimpact.auth.security.UserPrincipal;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.common.response.ApiResponse;
import com.changeimpact.history.repository.CommitFileChangeRepository;
import com.changeimpact.repository.entity.Commit;
import com.changeimpact.repository.repository.CommitRepository;
import com.changeimpact.repository.repository.RepositoryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/** Commit list (for commit pickers) and historical change intelligence. */
@RestController
@RequestMapping("/api/v1/repositories/{repositoryId}")
@RequiredArgsConstructor
@SecurityRequirement(name = "BearerAuth")
@Tag(name = "History", description = "Commits, churn and historical change intelligence")
public class HistoryController {

    private final RepositoryRepository repositoryRepository;
    private final CommitRepository commitRepository;
    private final CommitFileChangeRepository fileChangeRepository;

    @GetMapping("/commits")
    @Operation(summary = "Commits collected for the repository (newest first)")
    public ResponseEntity<ApiResponse<Page<Map<String, Object>>>> commits(@AuthenticationPrincipal UserPrincipal principal,
                                                                          @PathVariable UUID repositoryId,
                                                                          @RequestParam(defaultValue = "0") int page,
                                                                          @RequestParam(defaultValue = "50") int size) {
        validate(principal, repositoryId);
        Page<Map<String, Object>> result = commitRepository
                .findByRepositoryIdOrderByCommitDateDesc(repositoryId, PageRequest.of(page, Math.min(size, 200)))
                .map(HistoryController::commitView);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/history")
    @Operation(summary = "Weekly commit activity, most frequently changed files, revert and bug-fix counts (heuristic labels)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> history(@AuthenticationPrincipal UserPrincipal principal,
                                                                    @PathVariable UUID repositoryId,
                                                                    @RequestParam(defaultValue = "52") int weeks) {
        validate(principal, repositoryId);
        int w = Math.max(1, Math.min(weeks, 520));
        List<Map<String, Object>> weekly = new ArrayList<>();
        long commits = 0, bugfix = 0, reverts = 0;
        for (Object[] row : commitRepository.weeklyActivity(repositoryId, w)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("week", toInstant(row[0]));
            m.put("commits", ((Number) row[1]).longValue());
            m.put("bugfixCommits", ((Number) row[2]).longValue());
            m.put("revertCommits", ((Number) row[3]).longValue());
            commits += ((Number) row[1]).longValue();
            bugfix += ((Number) row[2]).longValue();
            reverts += ((Number) row[3]).longValue();
            weekly.add(m);
        }
        List<Map<String, Object>> hotspots = new ArrayList<>();
        for (Object[] row : fileChangeRepository.topChurnedFiles(repositoryId, 15)) {
            hotspots.add(Map.of("path", row[0], "commits", ((Number) row[1]).longValue(),
                    "changedLines", row[2] == null ? 0L : ((Number) row[2]).longValue(),
                    "bugfixCommits", row[3] == null ? 0L : ((Number) row[3]).longValue()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("weeks", w);
        body.put("scannedCommits", commitRepository.countByRepositoryIdAndHistoryScannedTrue(repositoryId));
        body.put("commitsInWindow", commits);
        body.put("bugfixCommitsInWindow", bugfix);
        body.put("revertCommitsInWindow", reverts);
        body.put("weekly", weekly);
        body.put("mostChangedFiles", hotspots);
        body.put("note", "Bug-fix and revert flags are heuristic labels from commit messages, not verified outcomes.");
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    private void validate(UserPrincipal principal, UUID repositoryId) {
        repositoryRepository.findByIdAndOwnerIdAndDeletedAtIsNull(repositoryId, principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repositoryId));
    }

    private static Map<String, Object> commitView(Commit c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sha", c.getSha());
        m.put("shortSha", c.getSha().substring(0, Math.min(7, c.getSha().length())));
        m.put("message", c.getMessage() == null ? "" : c.getMessage().lines().findFirst().orElse(""));
        m.put("author", c.getAuthorName());
        m.put("date", c.getCommitDate());
        m.put("parents", c.getParentShas());
        m.put("additions", c.getAdditions());
        m.put("deletions", c.getDeletions());
        m.put("filesChanged", c.getAddedFiles() + c.getModifiedFiles() + c.getDeletedFiles());
        m.put("merge", c.getIsMerge());
        m.put("revert", c.getIsRevert());
        m.put("bugfix", c.getIsBugfix());
        return m;
    }

    private static Instant toInstant(Object o) {
        if (o instanceof Instant i) return i;
        if (o instanceof OffsetDateTime odt) return odt.toInstant();
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant();
        return null;
    }
}
