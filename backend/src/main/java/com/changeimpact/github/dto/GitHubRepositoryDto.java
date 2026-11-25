package com.changeimpact.github.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GitHub repository data returned from the GitHub REST API.
 * Maps to the response from GET /repos/{owner}/{repo}.
 */
public record GitHubRepositoryDto(
        Long id,
        String name,
        @JsonProperty("full_name") String fullName,
        String description,
        @JsonProperty("clone_url") String cloneUrl,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("default_branch") String defaultBranch,
        String language,
        @JsonProperty("private") boolean isPrivate,
        @JsonProperty("fork") boolean isFork,
        @JsonProperty("forks_count") Integer forksCount,
        @JsonProperty("stargazers_count") Integer stargazersCount,
        Integer size,
        @JsonProperty("owner") OwnerDto owner
) {
    public record OwnerDto(String login, String type) {}
}

