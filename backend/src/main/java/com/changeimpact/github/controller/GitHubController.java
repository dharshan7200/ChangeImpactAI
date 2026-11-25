package com.changeimpact.github.controller;

import com.changeimpact.auth.security.UserPrincipal;
import com.changeimpact.common.response.ApiResponse;
import com.changeimpact.github.dto.ConnectGitHubAccountRequest;
import com.changeimpact.github.dto.ConnectedAccountResponse;
import com.changeimpact.github.dto.GitHubRepositoryDto;
import com.changeimpact.github.service.GitHubAccountService;
import com.changeimpact.repository.entity.ConnectedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for managing connected GitHub accounts and inspecting remote repositories.
 */
@RestController
@RequestMapping("/api/v1/github")
@RequiredArgsConstructor
@SecurityRequirement(name = "BearerAuth")
@Tag(name = "GitHub", description = "GitHub accounts and repository browsing")
public class GitHubController {

    private final GitHubAccountService gitHubAccountService;
    private final com.changeimpact.github.service.GitHubOAuthService oauthService;

    public record OAuthCallbackRequest(String code, String state) {}

    @GetMapping("/oauth/config")
    @Operation(summary = "Whether \"Connect with GitHub\" (OAuth App) is configured, with its scopes and callback URL")
    public ApiResponse<java.util.Map<String, Object>> oauthConfig() {
        return ApiResponse.success(oauthService.status());
    }

    @GetMapping("/oauth/authorize")
    @Operation(summary = "GitHub consent URL for the current user (signed, single-use state, valid 10 minutes)")
    public ApiResponse<java.util.Map<String, String>> oauthAuthorize(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(java.util.Map.of("url", oauthService.authorizationUrl(principal.getId())));
    }

    @PostMapping("/oauth/callback")
    @Operation(summary = "Complete the OAuth flow: verify state, exchange the code server-side, connect the account")
    public ResponseEntity<ApiResponse<ConnectedAccountResponse>> oauthCallback(
            @AuthenticationPrincipal UserPrincipal principal, @RequestBody OAuthCallbackRequest request) {
        ConnectedAccount account = oauthService.complete(principal.getId(), request.code(), request.state());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("GitHub account connected", ConnectedAccountResponse.from(account)));
    }

    @PostMapping("/connect")
    @Operation(summary = "Connect a GitHub account using an OAuth or Personal Access Token")
    public ResponseEntity<ApiResponse<ConnectedAccountResponse>> connectAccount(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ConnectGitHubAccountRequest request) {

        ConnectedAccount account = gitHubAccountService.connectGitHubAccount(principal.getId(), request.accessToken());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("GitHub account connected successfully", ConnectedAccountResponse.from(account)));
    }

    @GetMapping("/accounts")
    @Operation(summary = "List all active GitHub accounts connected to current user")
    public ResponseEntity<ApiResponse<List<ConnectedAccountResponse>>> listAccounts(
            @AuthenticationPrincipal UserPrincipal principal) {

        List<ConnectedAccountResponse> accounts = gitHubAccountService.listActiveConnections(principal.getId())
                .stream()
                .map(ConnectedAccountResponse::from)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(accounts));
    }

    @GetMapping("/accounts/{accountId}/repos")
    @Operation(summary = "List remote repositories accessible to a connected GitHub account")
    public ResponseEntity<ApiResponse<List<GitHubRepositoryDto>>> listRemoteRepositories(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "1") int page) {

        List<GitHubRepositoryDto> repos = gitHubAccountService.listRepositories(principal.getId(), accountId, page);
        return ResponseEntity.ok(ApiResponse.success(repos));
    }

    @DeleteMapping("/accounts/{accountId}")
    @Operation(summary = "Disconnect a GitHub account")
    public ResponseEntity<ApiResponse<Void>> disconnectAccount(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID accountId) {

        gitHubAccountService.disconnectAccount(principal.getId(), accountId);
        return ResponseEntity.ok(ApiResponse.success("GitHub account disconnected successfully"));
    }
}

