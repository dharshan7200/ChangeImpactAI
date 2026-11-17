package com.changeimpact.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for exchanging a refresh token for a new access token.
 */
public record RefreshTokenRequest(
        @NotBlank(message = "Refresh token is required")
        String refreshToken
) {}

