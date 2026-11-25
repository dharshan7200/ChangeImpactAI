package com.changeimpact.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Response DTO returned after successful authentication.
 *
 * <p>The access token is a short-lived JWT (default 15 min).
 * The refresh token is long-lived (default 7 days) and must be stored
 * securely by the client (httpOnly cookie is recommended).
 *
 * <p>{@code refreshToken} is omitted from the response when {@code null}
 * (e.g., when only refreshing an access token without rotating the refresh token).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthResponse(
        String accessToken,
        String refreshToken,
        long accessTokenExpiresInMs,
        UserSummary user
) {

    public record UserSummary(
            UUID id,
            String email,
            String username,
            String displayName,
            String avatarUrl
    ) {}
}

