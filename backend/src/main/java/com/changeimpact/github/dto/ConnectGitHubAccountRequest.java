package com.changeimpact.github.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for linking a GitHub account via an OAuth token or Personal Access Token.
 */
public record ConnectGitHubAccountRequest(
        @NotBlank(message = "GitHub access token is required")
        String accessToken
) {}

