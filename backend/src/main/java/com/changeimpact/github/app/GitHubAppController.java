package com.changeimpact.github.app;

import com.changeimpact.auth.security.UserPrincipal;
import com.changeimpact.common.config.AppProperties;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.common.response.ApiResponse;
import com.changeimpact.github.webhook.WebhookDelivery;
import com.changeimpact.github.webhook.WebhookDeliveryRepository;
import com.changeimpact.repository.entity.Repository;
import com.changeimpact.repository.repository.RepositoryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** GitHub App status, setup information, recent webhook deliveries and pull requests of the user's repositories. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "BearerAuth")
@Tag(name = "GitHub App", description = "Pull request automation status, deliveries and pull requests")
public class GitHubAppController {

    private final AppProperties appProperties;
    private final GitHubAppAuthService authService;
    private final GitHubAppInstallationRepository installationRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final PullRequestRepository pullRequestRepository;
    private final RepositoryRepository repositoryRepository;

    @GetMapping("/github/app")
    @Operation(summary = "GitHub App configuration status and which of your repositories it covers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(@AuthenticationPrincipal UserPrincipal principal) {
        AppProperties.GitHub gh = appProperties.getGithub();
        List<Repository> repos = repositoryRepository.findByOwnerIdAndDeletedAtIsNullOrderByFullNameAsc(principal.getId());
        List<GitHubAppInstallation> installations = installationRepository.findAll();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appConfigured", authService.isConfigured());
        body.put("webhookSecretConfigured", gh.getWebhookSecret() != null && !gh.getWebhookSecret().isBlank());
        body.put("checksEnabled", gh.getChecks().isEnabled());
        body.put("checkName", gh.getChecks().getName());
        body.put("failOn", gh.getChecks().getFailOn());
        body.put("analyzeDraftPullRequests", gh.isAnalyzeDraftPullRequests());
        body.put("webhookPath", "/api/v1/webhooks/github");
        body.put("detailsLinks", gh.getPublicUrl() != null && !gh.getPublicUrl().isBlank());
        body.put("installUrl", gh.getAppSlug() != null && !gh.getAppSlug().isBlank()
                ? "https://github.com/apps/" + gh.getAppSlug().trim() + "/installations/new" : null);
        body.put("requiredPermissions", Map.of("checks", "write", "contents", "read", "metadata", "read", "pull_requests", "read"));
        body.put("requiredEvents", List.of("pull_request", "push"));
        body.put("repositories", repos.stream().map(r -> {
            Optional<GitHubAppInstallation> inst = installations.stream().filter(i -> i.covers(r.getFullName())).findFirst();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("repositoryId", r.getId());
            m.put("fullName", r.getFullName());
            m.put("installed", inst.isPresent());
            m.put("installationId", inst.map(GitHubAppInstallation::getInstallationId).orElse(null));
            m.put("suspended", inst.map(GitHubAppInstallation::isSuspended).orElse(false));
            return m;
        }).toList());
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @GetMapping("/github/app/deliveries")
    @Operation(summary = "Last 50 webhook deliveries for your repositories")
    public ResponseEntity<ApiResponse<List<WebhookDelivery>>> deliveries(@AuthenticationPrincipal UserPrincipal principal) {
        List<String> names = repositoryRepository.findByOwnerIdAndDeletedAtIsNullOrderByFullNameAsc(principal.getId())
                .stream().map(Repository::getFullName).toList();
        return ResponseEntity.ok(ApiResponse.success(names.isEmpty() ? List.of()
                : deliveryRepository.findTop50ByRepositoryFullNameInOrderByReceivedAtDesc(names)));
    }

    @GetMapping("/repositories/{repositoryId}/pull-requests")
    @Operation(summary = "Pull requests seen through webhooks, with the latest analysis and check run state")
    public ResponseEntity<ApiResponse<List<PullRequest>>> pullRequests(@AuthenticationPrincipal UserPrincipal principal,
                                                                     @PathVariable UUID repositoryId) {
        repositoryRepository.findByIdAndOwnerIdAndDeletedAtIsNull(repositoryId, principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Repository", repositoryId));
        return ResponseEntity.ok(ApiResponse.success(pullRequestRepository.findByRepositoryIdOrderByUpdatedAtDesc(repositoryId)));
    }
}
