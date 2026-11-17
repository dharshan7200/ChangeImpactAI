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
        @Pattern(regexp = "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$",
                 message = "Repository full name must follow the 'owner/repo' format")
        String fullName,

        String branch
) {}

