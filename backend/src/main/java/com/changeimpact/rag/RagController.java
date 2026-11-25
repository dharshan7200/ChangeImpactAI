package com.changeimpact.rag;

import com.changeimpact.ai.GeminiClient;
import com.changeimpact.auth.security.UserPrincipal;
import com.changeimpact.common.exception.BusinessException;
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

import java.util.*;

/** Code search index of a repository (AI features). */
@RestController
@RequestMapping("/api/v1/repositories/{repoId}/rag")
@RequiredArgsConstructor
@Tag(name = "AI search", description = "Code chunks, embeddings and hybrid search over the last indexed commit")
public class RagController {

    private final RagService rag;
    private final RagIndexJobService jobs;
    private final GeminiClient gemini;
    private final RepositoryRepository repositories;
    private final UserRepository users;

    public record SearchRequest(String query, Integer k, String mode) {}

    @GetMapping("/status")
    @Operation(summary = "Whether the last indexed commit is searchable (chunks, candidates, AI configured)")
    public ApiResponse<Map<String, Object>> status(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId) {
        Repository repo = repository(principal, repoId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("aiEnabled", gemini.enabled());
        m.put("commit", repo.getLastIndexedCommit());
        m.put("chunks", repo.getLastIndexedCommit() == null ? 0 : rag.chunkCount(repoId, repo.getLastIndexedCommit()));
        m.put("candidates", repo.getLastIndexedCommit() == null ? 0 : rag.candidateCount(repoId, repo.getLastIndexedCommit()));
        m.put("embeddingModel", gemini.embeddingModel());
        return ApiResponse.success(m);
    }

    @PostMapping("/index")
    @Operation(summary = "Chunk and embed the last indexed commit (background job, RAG_INDEX)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> index(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId) {
        AnalysisJob job = jobs.start(repository(principal, repoId), users.getReferenceById(principal.getId()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success("AI search index queued", Map.of("jobId", job.getId())));
    }

    @PostMapping("/search")
    @Operation(summary = "Code search; mode = auto (default: hybrid for code names, vector for natural language) | hybrid | fts | vector. Falls back to full-text when embeddings are unavailable (see note)")
    public ApiResponse<RagService.SearchResult> search(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId,
                                                    @RequestBody SearchRequest request) {
        Repository repo = repository(principal, repoId);
        if (request.query() == null || request.query().isBlank() || request.query().length() > 500) {
            throw new BusinessException("INVALID_QUERY", "Query must be 1–500 characters");
        }
        if (repo.getLastIndexedCommit() == null || rag.chunkCount(repoId, repo.getLastIndexedCommit()) == 0) {
            throw new BusinessException("RAG_NOT_INDEXED", "Build the AI search index of this repository first");
        }
        int k = request.k() == null ? 10 : Math.max(1, Math.min(50, request.k()));
        String mode = request.mode() == null ? "auto" : request.mode();
        if (!Set.of("auto", "hybrid", "fts", "vector").contains(mode)) throw new BusinessException("INVALID_MODE", "mode must be auto, hybrid, fts or vector");
        return ApiResponse.success(rag.find(repoId, repo.getLastIndexedCommit(), request.query().strip(), k, mode));
    }

    private Repository repository(UserPrincipal principal, UUID repoId) {
        return repositories.findByIdAndOwnerIdAndDeletedAtIsNull(repoId, principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repoId));
    }
}
