package com.changeimpact.github.client;

import com.changeimpact.common.exception.GitHubIntegrationException;
import com.changeimpact.github.dto.GitHubRepositoryDto;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * HTTP client for the GitHub REST API v3.
 *
 * <p>Uses Spring WebClient for non-blocking HTTP. All calls are executed
 * synchronously here (block()) because the calling services are synchronous.
 * When the system moves to reactive programming, these can be made non-blocking.
 *
 * <p>SSRF protection: This client only connects to {@code api.github.com}.
 * User-supplied repository URLs are never used directly as HTTP targets;
 * they are passed to JGit which handles Git protocol operations in a
 * sandboxed workspace directory.
 */
@Component
@Slf4j
public class GitHubApiClient {

    private final WebClient.Builder webClientBuilder;
    private final String apiBaseUrl;

    public GitHubApiClient(WebClient.Builder webClientBuilder,
                           @org.springframework.beans.factory.annotation.Value("${app.github.api-base-url:https://api.github.com}") String apiBaseUrl) {
        this.webClientBuilder = webClientBuilder;
        // the configured API (GitHub Enterprise); it was hard-coded to api.github.com before
        this.apiBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
    }

    private WebClient clientFor(String token) {
        return webClientBuilder
                .baseUrl(apiBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /**
     * Returns the authenticated user's GitHub profile.
     */
    public GitHubUserDto getAuthenticatedUser(String accessToken) {
        return clientFor(accessToken)
                .get()
                .uri("/user")
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .map(body -> new GitHubIntegrationException(
                                        "GitHub /user request failed: " + body,
                                        response.statusCode().value())))
                .bodyToMono(GitHubUserDto.class)
                .block();
    }

    /**
     * Returns repositories accessible to the authenticated user.
     * Fetches the first page (up to 100 repos).
     */
    public List<GitHubRepositoryDto> listUserRepositories(String accessToken, int page) {
        return clientFor(accessToken)
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/user/repos")
                        .queryParam("per_page", 30)
                        .queryParam("page", page)
                        .queryParam("sort", "updated")
                        .queryParam("type", "all")
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new GitHubIntegrationException(
                                        "Failed to list repositories: " + body,
                                        response.statusCode().value()))))
                .bodyToFlux(GitHubRepositoryDto.class)
                .collectList()
                .block();
    }

    /**
     * Returns metadata for a specific repository by owner and repo name.
     */
    public GitHubRepositoryDto getRepository(String accessToken, String owner, String repo) {
        return clientFor(accessToken)
                .get()
                .uri("/repos/{owner}/{repo}", owner, repo)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new GitHubIntegrationException(
                                        "Repository not found or not accessible: " + owner + "/" + repo,
                                        response.statusCode().value()))))
                .bodyToMono(GitHubRepositoryDto.class)
                .block();
    }

    /**
     * Returns the list of branches for a repository.
     */
    public List<GitHubBranchDto> listBranches(String accessToken, String owner, String repo) {
        return clientFor(accessToken)
                .get()
                .uri("/repos/{owner}/{repo}/branches?per_page=100", owner, repo)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new GitHubIntegrationException(
                                        "Failed to list branches for " + owner + "/" + repo,
                                        response.statusCode().value()))))
                .bodyToFlux(GitHubBranchDto.class)
                .collectList()
                .block();
    }

    /**
     * Returns commits for a repository branch (most recent first).
     */
    public List<GitHubCommitDto> listCommits(String accessToken, String owner, String repo,
                                               String branch, int perPage, int page) {
        return clientFor(accessToken)
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/commits")
                        .queryParam("sha", branch)
                        .queryParam("per_page", perPage)
                        .queryParam("page", page)
                        .build(owner, repo))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new GitHubIntegrationException(
                                        "Failed to list commits for " + owner + "/" + repo,
                                        response.statusCode().value()))))
                .bodyToFlux(GitHubCommitDto.class)
                .collectList()
                .block();
    }

    // ---- Inner DTOs for GitHub API responses ----
    // These are package-private; mapped to domain DTOs by the service layer.

    public record GitHubUserDto(
            Long id,
            String login,
            String name,
            @JsonProperty("avatar_url") String avatarUrl,
            String email
    ) {}

    public record GitHubBranchDto(
            String name,
            GitHubCommitRef commit,
            @JsonProperty("protected") boolean isProtected
    ) {}

    public record GitHubCommitRef(String sha) {}

    public record GitHubCommitDto(
            String sha,
            GitHubCommitDetail commit,
            GitHubAuthorDto author,
            List<GitHubParentDto> parents
    ) {}

    public record GitHubCommitDetail(
            String message,
            GitHubGitUserDto author,
            GitHubGitUserDto committer
    ) {}

    public record GitHubGitUserDto(
            String name,
            String email,
            String date
    ) {}

    public record GitHubAuthorDto(String login) {}

    public record GitHubParentDto(String sha) {}
}

