package com.changeimpact.repository.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * Request payload for connecting a repository from a connected GitHub account.
 */
public record ConnectRepositoryRequest(
        UUID connectedAccountId,

        @NotBlank(message = "Repository full name is required")
        @Pattern(regexp = "^(https://github\\.com/)?[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?(\\.git)?/?$",
                 message = "Repository must be 'owner/repo' or a https://github.com/owner/repo URL")
        String fullName,

        String branch
) {}

