package com.changeimpact.ai.assistant;

import com.changeimpact.auth.security.UserPrincipal;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.common.response.ApiResponse;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.repository.RepositoryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/repositories/{repoId}/assistant")
@RequiredArgsConstructor
@Tag(name = "Assistant", description = "Questions about a repository, answered from retrieved code with validated citations")
public class AssistantController {

    private final AssistantService service;
    private final RepositoryRepository repositories;

    public record AskRequest(String question) {}

    @PostMapping("/ask")
    @Operation(summary = "Answer a question from retrieved code + dependency facts; citations validated")
    public ApiResponse<Map<String, Object>> ask(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId,
                                                @RequestBody AskRequest request) {
        Repository repo = repository(principal, repoId);
        return ApiResponse.success(service.ask(repoId, principal.getId(), repo.getLastIndexedCommit(), request.question()));
    }

    @GetMapping("/history")
    @Operation(summary = "Your last 20 questions and answers about this repository")
    public ApiResponse<List<Map<String, Object>>> history(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId) {
        repository(principal, repoId);
        return ApiResponse.success(service.history(repoId, principal.getId()));
    }

    private Repository repository(UserPrincipal principal, UUID repoId) {
        return repositories.findByIdAndOwnerIdAndDeletedAtIsNull(repoId, principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repoId));
    }
}
