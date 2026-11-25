package com.changeimpact.symbols.browser;

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

import java.util.UUID;

/** Read-only code browser over the analysed files of a commit. */
@RestController
@RequestMapping("/api/v1/repositories/{repoId}/source")
@RequiredArgsConstructor
@Tag(name = "Code browser", description = "Analysed source files, their text, symbols and resolved references (read-only)")
public class SourceBrowserController {

    private final SourceBrowserService service;
    private final RepositoryRepository repositories;

    @GetMapping("/files")
    @Operation(summary = "Analysed source files of a commit (default: last indexed)")
    public ApiResponse<SourceBrowserService.FileList> files(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId,
                                                            @RequestParam(required = false) String commit) {
        return ApiResponse.success(service.files(repository(principal, repoId), commit));
    }

    @GetMapping("/file")
    @Operation(summary = "Text of one analysed file at a commit, with its symbols and resolved references (≤ 1 MB, text only)")
    public ApiResponse<SourceBrowserService.FileView> file(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID repoId,
                                                           @RequestParam String path, @RequestParam(required = false) String commit) {
        return ApiResponse.success(service.file(repository(principal, repoId), commit, path));
    }

    private Repository repository(UserPrincipal principal, UUID repoId) {
        return repositories.findByIdAndOwnerIdAndDeletedAtIsNull(repoId, principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repoId));
    }
}
